(function () {
  var form = document.querySelector('form.form');
  if (!form) return;
  form.addEventListener('submit', function () {
    var btn = form.querySelector('button[type="submit"]');
    if (btn) { btn.disabled = true; btn.textContent = 'Sending…'; }
  });
  var first = form.querySelector('.error');
  if (first && first.parentElement) {
    var input = first.parentElement.querySelector('input,select,textarea');
    if (input) input.focus();
  }
})();
