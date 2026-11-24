// Approve / Deny buttons on the dashboard. They call the approval API
// (POST /api/approvals/{decisionId}/approve|deny); the dashboard itself never changes anything.
(function () {
  const nameInput = document.getElementById('reviewer');
  try {
    const saved = localStorage.getItem('strata.reviewer');
    if (nameInput && saved) nameInput.value = saved;
  } catch (e) { /* storage may be unavailable */ }

  document.querySelectorAll('[data-decision-id]').forEach(function (card) {
    const id = card.getAttribute('data-decision-id');
    const message = card.querySelector('.message');
    card.querySelectorAll('button[data-verdict]').forEach(function (button) {
      button.addEventListener('click', async function () {
        const verdict = button.getAttribute('data-verdict');
        const by = nameInput && nameInput.value.trim() ? nameInput.value.trim() : 'dashboard';
        try { localStorage.setItem('strata.reviewer', by); } catch (e) { /* ignore */ }
        card.querySelectorAll('button').forEach(function (b) { b.disabled = true; });
        message.className = 'message';
        message.textContent = verdict === 'approve' ? 'Approving…' : 'Denying…';
        try {
          const response = await fetch('/api/approvals/' + encodeURIComponent(id) + '/' + verdict, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ by: by })
          });
          if (response.ok) {
            message.textContent = verdict === 'approve' ? 'Approved. Running the action…' : 'Denied.';
            setTimeout(function () { window.location.reload(); }, 1500);
            return;
          }
          let reason = response.status + ' ' + response.statusText;
          try {
            const body = await response.json();
            if (body && (body.message || body.error)) reason = response.status + ': ' + (body.message || body.error);
          } catch (e) { /* not JSON */ }
          if (response.status === 404 && /No static resource|Not Found/i.test(reason)) {
            reason = 'the approval API is not available on this healer yet';
          }
          throw new Error(reason);
        } catch (err) {
          message.className = 'message error';
          message.textContent = 'Could not ' + verdict + ': ' + err.message;
          card.querySelectorAll('button').forEach(function (b) { b.disabled = false; });
        }
      });
    });
  });

  // Refresh every 15 s so new incidents show up, unless the engineer is typing or acting.
  setInterval(function () {
    const busy = document.activeElement && document.activeElement.tagName === 'INPUT';
    const working = document.querySelector('button:disabled');
    if (!busy && !working) window.location.reload();
  }, 15000);
})();
