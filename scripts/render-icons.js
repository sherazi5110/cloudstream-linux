// Renders packaging/linux/cloudstream-linux.svg to the PNG icons the app and packages use.
// Needs Playwright with Chromium: node scripts/render-icons.js
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const svg = fs.readFileSync(path.join(root, 'packaging/linux/cloudstream-linux.svg'), 'utf8');
const outputs = [
  ['packaging/linux/cloudstream-linux.png', 512],
  ['app/src/main/resources/ui/icon.png', 256],
];

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [file, size] of outputs) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ', `<svg style="width:${size}px;height:${size}px;display:block" `)}</body></html>`);
    await page.screenshot({ path: path.join(root, file), omitBackground: true });
    console.log(`${file} ${size}x${size}`);
  }
  await browser.close();
})();
