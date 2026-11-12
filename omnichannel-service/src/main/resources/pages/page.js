(function () {
  var beacon = document.body.getAttribute('data-beacon') || '';
  function send(payload) {
    if (!beacon) return;
    try {
      fetch(beacon, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload), keepalive: true });
    } catch (e) { /* tracking never blocks the page */ }
  }
  if (beacon) send({ k: 'open' });
  var replies = document.querySelectorAll('[data-k="reply"]');
  for (var i = 0; i < replies.length; i++) {
    replies[i].addEventListener('click', function (ev) {
      if (!beacon) { ev.preventDefault(); return; }
      send({ k: 'reply', id: this.getAttribute('data-id') });
    });
  }
  var cta = document.querySelector('[data-k="cta"]');
  if (cta && !beacon) cta.addEventListener('click', function (ev) { ev.preventDefault(); });
  var open = document.getElementById('list-open'), sheet = document.getElementById('sheet'), close = document.getElementById('list-close');
  if (open && sheet) {
    open.addEventListener('click', function () { sheet.hidden = false; });
    if (close) close.addEventListener('click', function () { sheet.hidden = true; });
  }
  var loc = document.querySelector('[data-k="location"]');
  if (loc) {
    loc.addEventListener('click', function () {
      var fallback = loc.getAttribute('data-fallback') || '#';
      if (!beacon) return;
      if (!navigator.geolocation) { window.location.href = fallback; return; }
      loc.disabled = true;
      navigator.geolocation.getCurrentPosition(function (pos) {
        send({ k: 'location', lat: pos.coords.latitude, lng: pos.coords.longitude });
        loc.textContent = 'Location shared';
      }, function () {
        loc.disabled = false;
        window.location.href = fallback;
      }, { enableHighAccuracy: true, timeout: 15000 });
    });
  }
})();
