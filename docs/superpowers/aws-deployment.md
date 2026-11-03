# Running on AWS cheaply

Written 2026-11-03. Nothing is deployed yet; the app is being made feature-ready. The user wants to run it on AWS with
very little spend while looking for customers, and grow the setup only when customers need it. Prices are rough
figures to check against AWS's current pricing, not quotes.

## The starting setup

- **One small server** in the Mumbai region (`ap-south-1`, close to customers in India) running the app's jar:
  EC2 `t4g.medium` (2 vCPU, 4 GB; about $25 a month) or a Lightsail instance of the same size. 4 GB leaves room for the
  JVM and, if MongoDB runs on the same box, for Mongo too.
- **MongoDB**, one of:
  - on the same server: cheapest; you own backups and upgrades;
  - MongoDB Atlas in `ap-south-1`: the free tier (512 MB) is too small once chat media is stored in GridFS; the
    smallest paid tier is about $60 a month and brings backups and monitoring.
- **No Kafka, no Redis, no load balancer.** Background work (campaigns, history imports, automations) runs on queues
  in MongoDB with leases, so nothing else is needed. An AWS load balancer alone costs about $16 to $20 a month.

## What to set up on that server

- **A domain and HTTPS.** The console and the extension's WebSocket (`wss://`) need a certificate. Caddy or Nginx with
  Let's Encrypt on the server is free; put it in front of the app's port.
- **Firewall (security group):** open 443 (and 80 for the certificate) to the world, SSH only from your own address,
  never MongoDB's port.
- **Secrets** (database password, super-admin password, mail settings) in environment variables or AWS SSM Parameter
  Store's free tier, never in the repo.
- **Backups:** a nightly `mongodump` to S3 (a few cents a month at this size), with a lifecycle rule that deletes old
  ones. Test a restore once. The local database holds real WhatsApp messages: keep the bucket private.
- **The app as a service:** run the jar under systemd so it restarts on failure and on reboot. A deploy is: stop, swap
  the jar, start (stopping takes up to 30 s while consoles hold their event streams open; see `project-rules.md`).
- **The extension** must point at the server's address instead of localhost.

## Growing later, only when needed

- More customers or heavier use: move MongoDB to Atlas first, then a bigger server.
- Two app servers: add a load balancer. The MongoDB queues already stop two servers taking the same work. Check first
  that the WebSocket sessions of the extensions and the consoles' event streams work across servers (a device's
  session lives on the server it connected to).
- A busier event queue: the automation queue sits behind one interface so Amazon SQS (pay per use, very cheap at
  this scale) or Kafka can replace it without touching the rules.
