'use strict';
const button = document.querySelector('#copy-hash');
button.addEventListener('click', async () => {
  const hash = document.querySelector('#apk-hash').textContent.trim();
  const status = document.querySelector('#copy-status');
  try {
    if (!navigator.clipboard || !window.isSecureContext) throw new Error('Clipboard unavailable');
    await navigator.clipboard.writeText(hash);
    button.textContent = 'Скопировано ✓';
    status.textContent = 'Контрольная сумма скопирована';
  } catch {
    const range = document.createRange();
    range.selectNodeContents(document.querySelector('#apk-hash'));
    const selection = window.getSelection();
    selection.removeAllRanges(); selection.addRange(range);
    status.textContent = 'Выделено — скопируйте вручную';
  }
});
