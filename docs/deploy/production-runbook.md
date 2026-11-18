# Production runbook

The steps to set up and run the production server, so nothing is forgotten at go-live. Started 2026-11-18. Nothing
is deployed to production yet. The server's general shape (one small AWS server in Mumbai, MongoDB, backups, systemd)
is in `docs/superpowers/aws-deployment.md`; this file holds the exact steps.

In the examples the platform's own address is `https://go.ourcrm.in` and the customer is Bimal Institute. Put the real
names in their place.

## 1. Before go-live: checklist

- [ ] A server with a fixed public IP (an Elastic IP on EC2, a static IP on Lightsail). Customers' DNS records point
      at our host **name**, so a new IP later only means changing our own record, but a fixed IP avoids even that.
- [ ] Our DNS: `go.ourcrm.in  A  <server IP>`.
- [ ] Firewall (security group): 80 and 443 open to everyone, SSH only from our own address. The app's port (8080)
      and MongoDB's port are never open to the internet.
- [ ] Java 21 and the app's jar, run as a systemd service (see `aws-deployment.md`).
- [ ] Caddy installed and running with `docs/deploy/Caddyfile` (section 2).
- [ ] The platform address set: start the app once with `OMNICHANNEL_PAGES_BASE_URL=https://go.ourcrm.in` (it is
      stored), or set it later on the console's **Platform → Link address** page.
- [ ] The secrets sealing key moved out of the database's `appConfig` before go-live (see `project-rules.md` §2):
      today every database backup contains it.
- [ ] Nightly `mongodump` to a private S3 bucket, and one test restore.

## 2. Caddy: HTTPS for our address and for customers' link addresses

Caddy sits in front of the app on ports 80 and 443. It gets free certificates from Let's Encrypt and renews them by
itself, for our own address and for every customer subdomain that points at us (section 3, way 1). Before it gets a
certificate for a name it has not served before, it asks the app (`/internal/pages/host-allowed`); the app says yes
only for addresses saved on the console. A stranger pointing their domain at our server gets nothing.

### Install (Ubuntu or Debian, Caddy's official package)

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

The package creates the `caddy` service (starts on boot), reads `/etc/caddy/Caddyfile` and keeps certificates in
`/var/lib/caddy`. On another system, follow https://caddyserver.com/docs/install and make sure Caddy runs as a
service that starts on boot. Check these commands against that page at install time; they change rarely.

### Configure

```bash
sudo cp docs/deploy/Caddyfile /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

- The Caddyfile expects the app on `localhost:8080`. If the app runs on another port, change both lines.
- Caddy must be the only program listening on 80 and 443. The app itself stays on 8080, closed to the internet.
- Caddy keeps the visitor's original host name when it passes a request on, so the app's
  `omnichannel.pages.trust-forwarded-host` stays `false`.
- The app answers the certificate question only to callers on the same machine. If Caddy ever runs on a different
  machine, add that machine's address to `omnichannel.pages.ask-from`.

### Check it works

```bash
systemctl status caddy                      # active (running)
curl -sI https://go.ourcrm.in/ | head -1    # HTTP/2 200: the console, over HTTPS
journalctl -u caddy --since "10 min ago"    # certificate requests and errors
```

The first request to a new name takes a few seconds while Caddy fetches its certificate.

### Things to know

- Let's Encrypt limits how many certificates one domain can get per week. The app's yes/no answer keeps us well
  inside that, since only saved addresses get one.
- Back up `/var/lib/caddy` with the server, or Caddy simply fetches the certificates again after a rebuild.
- Renewal is automatic. Nothing to schedule.

## 3. Setting up a customer's link address

A brand's message and form links can use the customer's own domain, so someone asking Bimal Institute lands on a Bimal
Institute address. The super admin sets it on the customer's page (**Link addresses → Set…**). The console says which
of the two ways applies as the address is typed.

There are two ways. The difference is who holds the certificate: **we do** (way 1) or **the customer does** (way 2).

### Way 1: a subdomain, and we issue the certificate

Example: `https://user-task.bimalinstitute.com`. Use it when the customer can add a DNS record and gives that one
subdomain to us. It is the least work for them. It needs Caddy (section 2).

1. **Customer:** in their DNS provider (GoDaddy, Cloudflare and so on), add one record:
   `user-task.bimalinstitute.com  CNAME  go.ourcrm.in`
2. **Super admin:** customer page → **Link addresses** → **Set…** for the brand → `https://user-task.bimalinstitute.com`
   → **Save**.
3. **Automatically:** the app sends a check to the new address. It reaches our server through the customer's record;
   Caddy sees a new name, asks the app (yes, it was just saved), fetches the certificate (usually seconds, at most
   about a minute), and the check comes through. Status: **Working**.
4. If the first check failed only because the certificate was still on its way, press **Check** again.
5. New links of that brand now look like `https://user-task.bimalinstitute.com/t/…`. Links sent before keep the
   address they were sent with.

If it fails:

- "The name does not exist": the DNS record is missing or not live yet (it can take minutes to hours). Wait, then
  **Check**.
- The customer uses Cloudflare with the orange cloud (proxy) on: Cloudflare answers instead of us. Ask them to set that
  record to **DNS only** (grey cloud).
- The customer's domain allows only certain certificate providers (a CAA record): they must allow `letsencrypt.org`.
- To look by hand: `curl -vI https://user-task.bimalinstitute.com/ 2>&1 | grep -iE "issuer|HTTP/"` shows the
  certificate's issuer (Let's Encrypt when it is ours) and our answer (404 with no body, since only link pages are
  served on a customer's domain).

### Way 2: a folder on the customer's own website, with their certificate

Example: `https://bimalinstitute.com/wa`. Use it when the customer wants their main domain itself (a main domain
cannot take a CNAME record; that is a DNS rule) or the domain already runs their website. Their web server keeps its
own certificate and passes link requests on to us. We issue nothing, and Caddy is not involved for this customer.

1. **Customer:** on their web server, forward everything under `/wa/t/` and `/wa/f/` to our address with `/wa`
   removed:
   - `https://bimalinstitute.com/wa/t/abc` → `https://go.ourcrm.in/t/abc`
   - `https://bimalinstitute.com/wa/f/xyz` → `https://go.ourcrm.in/f/xyz`

   With nginx:

   ```nginx
   location /wa/t/ {
       proxy_pass https://go.ourcrm.in/t/;
       proxy_set_header Host go.ourcrm.in;
       proxy_ssl_server_name on;
   }
   location /wa/f/ {
       proxy_pass https://go.ourcrm.in/f/;
       proxy_set_header Host go.ourcrm.in;
       proxy_ssl_server_name on;
   }
   ```

   It must be forwarding (their server fetches from us and answers), **not a redirect** (the browser sent on to our
   address): the check does not follow redirects, and the visitor would see our domain anyway.
2. **Super admin:** **Set…** → `https://bimalinstitute.com/wa` → **Save**.
3. **Automatically:** the check goes to their server, is forwarded to us, and we answer. Status: **Working** at once,
   since their certificate already exists.
4. New links look like `https://bimalinstitute.com/wa/t/…`.

If it fails: the forwarding is missing, keeps `/wa` in the path, or redirects instead of forwarding. The reason is
shown beside the status. Their certificate is theirs to renew; if it expires their whole website stops too.

### Side by side

| | Way 1: subdomain | Way 2: folder |
|---|---|---|
| Example | `https://user-task.bimalinstitute.com` | `https://bimalinstitute.com/wa` |
| Customer's work | One DNS record | Forwarding on their web server |
| Certificate issued and renewed by | Us (Caddy, automatically) | The customer |
| Needs Caddy | Yes | No |
| First check | May need a second **Check** after a minute | Works at once if the forwarding is right |

### "Can't we add the record in our own DNS?"

No. Only the owner of `bimalinstitute.com` controls its DNS: when a phone opens `user-task.bimalinstitute.com` it asks
Bimal Institute's DNS provider, never ours. Someone with access to their domain has to act once. Ways to make that
easy:

1. **They add the CNAME record themselves** (about two minutes). Send them the exact line from way 1, step 1. This is
   the usual way.
2. **They give our team access to their DNS account** (or a login limited to DNS) and we add the record for them.
   For customers who are not technical.
3. **They hand that one subdomain to us once** with `NS` records ("delegation"); after that our DNS answers for it and
   we never need them again. Usually more than needed.

A subdomain of **our** domain (for example `https://bimal.ourcrm.in`) needs nothing from the customer: we add the
record, Caddy issues the certificate, and it works as way 1. But the link then shows our name, not theirs, which is
what this feature is meant to avoid. Keep it as a fallback for a customer who will not touch their DNS.

### The same for both ways

- An address is used only once its check passes. Until then, and whenever it fails, links use the platform address,
  so a mistake never stops sending.
- On a customer's domain the app answers only the link pages (`/t/…` and `/f/…`). The console and the API never show
  there.
- A check runs every day at 04:15. Three failures in a row mark an address **Failing**, and new links go back to the
  platform address.
- When an address is changed or removed, the customer must keep the old DNS record or forwarding for **90 days**, so
  links already sent keep opening. The console shows the exact end date under the brand.
- One customer's domain cannot be used by another customer (also for 90 days after they stop using it).
- Changing the platform's own address follows the same rule: keep the old one answering for 90 days.

More detail: the "Link addresses" section of `README.md`, and the spec
`docs/superpowers/specs/2026-11-18-link-addresses-design.md`.
