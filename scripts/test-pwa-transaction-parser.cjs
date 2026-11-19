const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync("app/index.html", "utf8");
const inlineScripts = [...html.matchAll(/<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)];
assert.ok(inlineScripts.length > 0, "PWA inline script must exist");

assert.ok(html.includes('id="togglePassword"'), "web login password must be hidden with a visibility toggle");
assert.ok(html.includes('const AUTH_REDIRECT_URL="https://arthanesia.github.io/app/";'), "auth emails must return to production URL");
assert.ok(html.includes('sb.auth.resend({type:"signup"'), "web app must support resending verification email");
assert.ok(!html.includes('id="goldGrams"') && !html.includes('id="goldBuybackManual"'), "gold ownership and manual buyback fields must be removed");
assert.ok(html.includes('resetPasswordForEmail'), "web login must expose password recovery");
assert.ok(html.includes('PASSWORD_RECOVERY'), "web app must handle the password recovery callback");
assert.ok(html.includes('function showPasswordRecovery()'), "password recovery callback must reveal the reset form");
assert.ok(html.includes('sb?.auth.onAuthStateChange(handleAuthStateChange);'), "auth session handling must use one state listener");
assert.ok(!html.includes('sb?.auth.getSession().then('), "parallel getSession entry must not race the recovery callback");
assert.ok(html.includes('function shouldEnterAppFromAuthEvent('), "auth event entry rules must be testable");
assert.ok(html.includes('identities.length===0'), "duplicate email sign-up must be recognized");
assert.ok(!html.includes('id="aiConsent"'), "AI analysis must not require per-request consent checkbox");

for (const [index, match] of inlineScripts.entries()) {
  new vm.Script(match[1], { filename: `app/index.html inline script ${index + 1}` });
}

const appScript = inlineScripts.map(match => match[1]).join("\n");
const authRuleSource = appScript.split("\n").find(line => line.trim().startsWith("function shouldEnterAppFromAuthEvent("));
assert.ok(authRuleSource, "auth recovery state rule must be present");
const authSandbox = {};
vm.runInNewContext(authRuleSource + "\nthis.authRule = shouldEnterAppFromAuthEvent;", authSandbox);
assert.equal(authSandbox.authRule("INITIAL_SESSION", { user: {} }, false), true, "normal saved sessions should enter the app");
assert.equal(authSandbox.authRule("SIGNED_IN", { user: {} }, false), true, "normal sign-ins should enter the app");
assert.equal(authSandbox.authRule("INITIAL_SESSION", { user: {} }, true), false, "recovery sessions must not be redirected into the app");
assert.equal(authSandbox.authRule("SIGNED_IN", { user: {} }, true), false, "recovery mode must not be overridden by a sign-in callback");
assert.equal(authSandbox.authRule("PASSWORD_RECOVERY", { user: {} }, false), false, "recovery events must render the reset form, not the app");
assert.equal(authSandbox.authRule("INITIAL_SESSION", null, false), false, "empty sessions must not enter the app");
assert.ok(html.includes('id="marketTarget"'), "converter must support selecting a target currency");
assert.ok(html.includes('id="fxRefresh"'), "converter must offer manual rate refresh");
assert.ok(html.includes('id="marketFxStamp"'), "converter must display rate observation time");
const fxRateHelperSource = appScript.split("\n").find(line => line.trim().startsWith("function convertCurrencyByIdrRates("));
assert.ok(fxRateHelperSource, "FX cross-conversion function must be present");
const fxSandbox = {};
vm.runInNewContext(fxRateHelperSource + "\nthis.convert = convertCurrencyByIdrRates;", fxSandbox);
const sampleRates = { IDR: 1, USD: 0.00006, EUR: 0.000055, SGD: 0.000077 };
assert.ok(Math.abs(fxSandbox.convert(1, "USD", "IDR", sampleRates) - 16666.6666667) < 0.01, "USD to IDR cross conversion must use the IDR base correctly");
assert.ok(Math.abs(fxSandbox.convert(250000, "IDR", "SGD", sampleRates) - 19.25) < 0.0001, "IDR to SGD conversion must use the IDR base correctly");
assert.ok(Math.abs(fxSandbox.convert(1, "USD", "EUR", sampleRates) - 0.9166666667) < 0.0001, "cross-currency conversion must work");
assert.equal(fxSandbox.convert(1, "USD", "CAD", sampleRates), null, "unsupported pair must not invent a rate");
const parseAmountStart = appScript.indexOf("function parseLocalAmount(");
const parseAmountEnd = appScript.indexOf("function formatConvertedCurrency(", parseAmountStart);
assert.ok(parseAmountStart >= 0 && parseAmountEnd > parseAmountStart, "locale-aware converter amount parser must exist");
const amountSandbox = {};
vm.runInNewContext(appScript.slice(parseAmountStart, parseAmountEnd) + "\nthis.parse = parseLocalAmount;", amountSandbox);
assert.equal(amountSandbox.parse("1.234,56"), 1234.56, "Indonesian decimal input must parse");
assert.equal(amountSandbox.parse("1,234.56"), 1234.56, "English decimal input must parse");

const parserStart = appScript.indexOf("function categoryFor");
const parserEnd = appScript.indexOf("async function readReceipt", parserStart);
assert.ok(parserStart >= 0 && parserEnd > parserStart, "PWA transaction parser functions must be present");

const parserSource = appScript.slice(parserStart, parserEnd) +
  "\nthis.__arthanesiaParser = { parseVoice, parseReceiptText, calculateIncomeZakat, normalizeReceiptAmount };";
const sandbox = {};
vm.runInNewContext(parserSource, sandbox, { filename: "app transaction parser" });
const { parseVoice, parseReceiptText, calculateIncomeZakat, normalizeReceiptAmount } = sandbox.__arthanesiaParser;

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
assert.equal(receipt.title, "Belanja bahan baku", "receipt description should be a short business summary, not a product list");


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

const receiptWithCardNumber = parseReceiptText(`
TOKO MAKMUR
TOTAL BAYAR Rp 33.000 KARTU 12345678
`);
assert.equal(receiptWithCardNumber.amount, 33000, "card reference after total must not replace payment amount");

const ocrZeroReceipt = parseReceiptText(`
TOKO MAKMUR
TOTAL BAYAR Rp 33.OOO
`);
assert.equal(ocrZeroReceipt.amount, 33000, "OCR letter O in amount should normalize to zero");

const splitLineReceipt = parseReceiptText(`
TOKO MAKMUR
Tepung Terigu 1 KG
Rp 15.000
Gula Pasir 1 KG
Rp 18.000
TOTAL BAYAR
Rp 33.000
`);
assert.equal(splitLineReceipt.amount, 33000, "TOTAL BAYAR followed by amount on next line should parse");
assert.equal(splitLineReceipt.title, "Belanja bahan baku");

const splitLineNoTotal = parseReceiptText(`
TOKO MAKMUR
Tepung Terigu 1 KG
Rp 15.000
Gula Pasir 1 KG
Rp 18.000
`);
assert.equal(splitLineNoTotal.amount, null, "item prices on following lines must not be guessed as receipt total");
assert.equal(splitLineNoTotal.title, "Belanja bahan baku", "receipt description must not list every product");


assert.equal(normalizeReceiptAmount("33.000,00"), 33000, "Indonesian decimal suffix should not multiply totals by 100");
assert.equal(normalizeReceiptAmount("1.234.567"), 1234567, "Indonesian thousands separators should be normalized");
assert.equal(normalizeReceiptAmount("33.OOO"), 33000, "OCR O characters should normalize to zero");


const decimalTotalReceipt = parseReceiptText(`
TOKO MAKMUR
Tepung Terigu 1 KG
Rp 15.000
TOTAL BAYAR
Rp 33.000,00
`);
assert.equal(decimalTotalReceipt.amount, 33000, "TOTAL BAYAR with decimal cents should parse as rupiah");

const amountDueReceipt = parseReceiptText(`
MINI MARKET
AMOUNT DUE
IDR 125,000
Cash 150,000
Change 25,000
`);
assert.equal(amountDueReceipt.amount, 125000, "AMOUNT DUE on a separate line should win");

const totalWithReferenceReceipt = parseReceiptText(`
TOKO MAKMUR
TOTAL BAYAR Rp 33.000 KARTU 12345678
`);
assert.equal(totalWithReferenceReceipt.amount, 33000, "payment reference after total must not replace the amount");

let incomeZakat = calculateIncomeZakat(7640144, 7640144);
assert.equal(incomeZakat.basis, "monthly");
assert.equal(incomeZakat.zakatAmount, 191004);

incomeZakat = calculateIncomeZakat(5000000, 91681728);
assert.equal(incomeZakat.basis, "annual", "variable income can qualify on the annual threshold");
assert.equal(incomeZakat.zakatAmount, 2292043);

console.log("PWA tests passed: auth recovery, voice, receipt OCR totals, FX, zakat nisab/haul and income thresholds.");
