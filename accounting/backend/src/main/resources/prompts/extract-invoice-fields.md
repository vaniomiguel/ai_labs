Identify the following fields in the complete sourceText. Use these exact JSON names and the supplied response schema. Apply the same definitions to every child in draft.invoices when there are independent receipts.

INVOICE FIELDS
- issuedOn: the printed invoice/receipt issue date, normalized to YYYY-MM-DD. Interpret clearly identified Portuguese day/month/year dates accordingly. Do not substitute a payment, service, travel or print date, invent a year, or resolve a genuinely ambiguous date by guessing. Use null when it cannot be identified.
- dueOn: an explicitly printed payment due date, normalized to YYYY-MM-DD. Do not calculate it from payment terms or issuedOn. If absent, use null.
- number: the invoice/receipt identifier. Preserve meaningful prefixes, letters, punctuation and leading zeros. Do not substitute an order, booking, customer-account or payment reference. If absent, use null.
- supplier: the issuer's business or personal name as printed, not the customer's name. Join clearly associated wrapped name lines only; use null when the issuer cannot be identified.
- supplierTaxId: the issuer's printed tax identification number, including its country prefix when present. Do not substitute the customer's NIF, an account number or another registration identifier. Use null when absent or uncertain.
- description: a concise factual description of the goods or services supported by the text. Do not invent a business purpose or expense category. Use null when unavailable.
- total: the printed final invoice amount including VAT, normalized to a decimal string with a period and two decimal places. Do not substitute a taxable base, subtotal, balance, change or tax amount. Do not derive a missing total; use null.
- net: the explicitly printed invoice taxable base/net amount before VAT, as a two-decimal string. Do not sum tax-rate rows or derive it by subtracting tax from total. Use null when no unambiguous overall amount is printed.
- tax: the explicitly printed overall VAT/tax amount, as a two-decimal string; a VAT rate such as 23% is not a monetary amount. Do not calculate tax or add individual tax rows. Use null when unavailable.
- discount: the explicitly printed overall monetary discount, as a two-decimal string. Do not convert percentages to money or invent zero when absent; use null.
- currency: the printed currency as an ISO code, for example EUR. The application's explicit default is EUR when no currency is printed. Preserve an explicitly different currency and report it in issues because the application operates in EUR.
- type: CREDIT_NOTE when the text explicitly identifies a credit note; otherwise INVOICE, including ordinary invoices and receipts. Do not infer a credit note from an isolated minus sign.
- relatedInvoiceId: an existing application UUID linking a credit note to its original invoice, retained only when supplied in saved data. A printed invoice number is not this UUID. Otherwise use null. For an unresolved credit note, quote any printed original-invoice reference in issues and request review.
- categories: always []; CategoryAiWorker assigns expense labels separately.

ITEM FIELDS
- lines: one object for each identifiable purchased item, retaining source order and legitimate repetitions. Use [] if there are no identifiable item rows; do not invent an item from the invoice total or tax summary.
- lines[n].description: the item's printed description, including clearly associated continuation lines. Use null for an unreadable description and explain the issue.
- lines[n].quantity: the explicitly printed quantity as a decimal string, preserving meaningful precision up to six decimal places. Do not infer quantity 1 when absent; use null.
- lines[n].unitPrice: the explicitly printed unit price as a decimal string, preserving meaningful precision up to six decimal places. Do not divide a line amount by quantity; use null when absent.
- lines[n].net, .tax, .discount: the corresponding explicitly printed monetary amounts for this item, as two-decimal strings. Do not convert rates, allocate invoice-level amounts or calculate missing values; use null.
- lines[n].total: the explicitly printed final amount for this item including VAT. A clearly pre-VAT line amount belongs in net. Do not calculate a missing gross amount; use null and report the unreadable or unavailable item total in issues.

FORMATS AND OUTPUT
- Normalize monetary punctuation without changing value: for example 1.234,56 becomes "1234.56". Retain printed signs and meaningful precision; never silently round a value that cannot be represented under the field rules. Report unresolved format ambiguity in issues.
- Missing optional fields are null, not empty strings, zeroes or invented values. Required arrays still appear as arrays. Include all required invoice fields and each item's required description and total fields even when a nullable value is null.
- invoices: use this optional array only for several independent receipts, with a complete invoice object per receipt. Apply the outer-field conventions in the system instructions.
- evidence: provide objects containing field and a literal source quote, with page as an explicitly known number or null and an optional reason. Include evidence for issuedOn, supplier, total and every item total; use the indexed paths specified in the system instructions.
- issues: an array of concrete unresolved problems requiring review; return [] when none remain. An absent optional due date, number, tax ID or optional amount alone is not a problem. Missing issue date, supplier or final total, unresolved credit-note links and unreadable item totals require an issue.
