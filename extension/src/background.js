async function toggle(tab) {
  if (!tab?.id) return;
  try { await chrome.tabs.sendMessage(tab.id, { type: 'gemini-anchor:toggle' }); }
  catch { /* A non-Gemini tab, or a tab opened before installation. */ }
}
chrome.action.onClicked.addListener(toggle);
chrome.commands.onCommand.addListener(async (command) => {
  if (command !== 'toggle-sidebar') return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  await toggle(tab);
});
