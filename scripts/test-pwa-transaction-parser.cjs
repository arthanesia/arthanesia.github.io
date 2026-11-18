const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync("app/index.html", "utf8");
const inlineScripts = [...html.matchAll(/<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)];
assert.ok(inlineScripts.length > 0, "PWA inline script must exist");

for (const [index, match] of inlineScripts.entries()) {
  new vm.Script(match[1], { filename: `app/index.html inline script ${index + 1}` });
}

const appScript = inlineScripts.map(match => match[1]).join("\n");
const parserStart = appScript.indexOf("function categoryFor");
const parserEnd = appScript.indexOf("async function getOcrWorker", parserStart);
assert.ok(parserStart >= 0 && parserEnd > parserStart, "PWA transaction parser functions must be present");

const parserSource = appScript.slice(parserStart, parserEnd) +
  "\nthis.__arthanesiaParser = { parseVoice, parseReceiptText };";
const sandbox = {};
vm.runInNewContext(parserSource, sandbox, { filename: "app transaction parser" });
const { parseVoice, parseReceiptText } = sandbox.__arthanesiaParser;

let parsed = parseVoice("pengeluaran 5000 beli bahan baku");
assert.equal(parsed.type, "expense");
assert.equal(parsed.amount, 5000);
assert.equal(parsed.title, "Beli bahan baku");
assert.equal(parsed.category, "Bahan Baku");

parsed = parseVoice("pengeluaran lima ribu beli bahan baku");
assert.equal(parsed.amount, 5000);
assert.equal(parsed.category, "Bahan Baku");

parsed = parseVoice("pengeluaran 5 ribu beli bahan baku");
assert.equal(parsed.amount, 5000);
assert.equal(parsed.category, "Bahan Baku");

parsed = parseVoice("pengeluaran 50 beli air minum");
assert.equal(parsed.amount, 50);
assert.equal(parsed.category, "Utilitas");

parsed = parseVoice("pemasukan 250000 jasa desain");
assert.equal(parsed.type, "income");
assert.equal(parsed.amount, 250000);
assert.equal(parsed.category, "Pendapatan Jasa");

const receipt = parseReceiptText(`
TOKO MAKMUR
Tepung Terigu 1 KG 15.000
Gula Pasir 1 KG 18.000
Subtotal Rp 31.000
TOTAL Rp 33.000
`);
assert.equal(receipt.amount, 33000, "grand total should take precedence over subtotal");
assert.equal(receipt.category, "Bahan Baku");
assert.match(receipt.title, /Tepung Terigu/);
assert.match(receipt.title, /Gula Pasir/);

console.log("PWA parser tests passed: voice amounts, categories, and multi-item receipt OCR.");
