// A classic script, not a module — see the comment where index.html loads it.
if (location.protocol === 'file:') {
  const el = document.getElementById('auth-error');
  el.textContent = 'Open this page through a local server, not as a file: run '
    + '"python -m http.server 5500" in the frontend folder, then visit http://localhost:5500';
  el.classList.add('show');
}
