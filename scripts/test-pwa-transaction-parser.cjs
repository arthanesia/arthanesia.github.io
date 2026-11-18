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
const parserEnd = appScript.indexOf("async function readReceipt", parserStart);
assert.ok(parserStart >= 0 && parserEnd > parserStart, "PWA transaction parser functions must be present");

const parserSource = appScript.slice(parserStart, parserEnd) +
  "\nthis.__arthanesiaParser = { parseVoice, parseReceiptText, calculateGoldZakat, calculateIncomeZakat };";
const sandbox = {};
vm.runInNewContext(parserSource, sandbox, { filename: "app transaction parser" });
const { parseVoice, parseReceiptText, calculateGoldZakat, calculateIncomeZakat } = sandbox.__arthanesiaParser;

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


const noTotalReceipt = parseReceiptText(`
TOKO MAKMUR
Tepung Terigu 1 KG 15.000
Gula Pasir 1 KG 18.000
`);
assert.equal(noTotalReceipt.amount, null, "product prices must not be mistaken for receipt total");

const labelledReceipt = parseReceiptText(`
TOKO MAKMUR
Tepung Terigu 1 KG 15.000
Subtotal Rp 15.000
PPN Rp 1.650
TOTAL BAYAR Rp 16.650
Tunai Rp 20.000
Kembali Rp 3.350
`);
assert.equal(labelledReceipt.amount, 16650, "TOTAL BAYAR should win over subtotal, tax, cash and change");

let goldZakat = calculateGoldZakat(85, 2272000, true);
assert.equal(goldZakat.meetsNisab, true);
assert.equal(goldZakat.eligible, true);
assert.equal(goldZakat.nisabValue, 193120000);
assert.equal(goldZakat.zakatAmount, 4828000);

goldZakat = calculateGoldZakat(100, 2272000, false);
assert.equal(goldZakat.meetsNisab, true);
assert.equal(goldZakat.eligible, false);
assert.equal(goldZakat.zakatAmount, 0, "haul is required");

goldZakat = calculateGoldZakat(84.99, 2272000, true);
assert.equal(goldZakat.meetsNisab, false);
assert.equal(goldZakat.zakatAmount, 0, "below 85 grams is not eligible");

goldZakat = calculateGoldZakat(100, 0, true);
assert.equal(goldZakat.eligible, false, "missing buyback price must not mark zakat as due");

let incomeZakat = calculateIncomeZakat(7640144, 7640144);
assert.equal(incomeZakat.basis, "monthly");
assert.equal(incomeZakat.zakatAmount, 191004);

incomeZakat = calculateIncomeZakat(5000000, 91681728);
assert.equal(incomeZakat.basis, "annual", "variable income can qualify on the annual threshold");
assert.equal(incomeZakat.zakatAmount, 2292043);

console.log("PWA tests passed: voice, OCR totals, zakat nisab/haul, income thresholds.");
