"""Safety checks for private corpus reporting, using synthetic reference data only."""

import unittest
from report_2016 import history_comparison, bank_comparison, application_summary


class RealCorpusReportTest(unittest.TestCase):
    def test_historical_comparison_keeps_missing_amount_distinct_from_zero(self):
        reference = {
            "rows": [
                {
                    "sheet": "Janeiro",
                    "rowNumber": 14,
                    "date": "2015-12-10",
                    "description": "Exemplo",
                    "received": None,
                    "payment": "4.00",
                    "balance": "6.00",
                }
            ],
            "controls": [
                {
                    "sheet": "Janeiro",
                    "opening": "10.00",
                    "received": "0.00",
                    "payment": "4.00",
                    "closing": "6.00",
                }
            ],
        }
        snapshot = {
            "history": [{**reference["rows"][0], "received": "0.00"}],
            "documents": [
                {
                    "metadata": {"kind": "HISTORY"},
                    "detail": {
                        "status": "DONE",
                        "draft": {
                            "sheets": [
                                {
                                    "name": "Janeiro",
                                    "controls": {
                                        k: v
                                        for k, v in reference["controls"][0].items()
                                        if k != "sheet"
                                    },
                                }
                            ]
                        },
                    },
                }
            ],
        }
        result = history_comparison(reference, snapshot)
        self.assertEqual([r["field"] for r in result["differences"]], ["received"])
        self.assertEqual(result["controlDifferences"], [])

    def test_equal_bank_transactions_are_counted_individually(self):
        row = {
            "bookedOn": "2020-01-01",
            "amount": "7.00",
            "direction": "DEBIT",
            "reviewStatus": "AUTOMATIC",
        }
        expected = {
            "bankMovements": {
                "sample.pdf": [[row["bookedOn"], row["amount"], row["direction"]]] * 2
            }
        }
        snapshot = {
            "documents": [
                {
                    "metadata": {"filename": "sample.pdf"},
                    "records": {"movements": [row]},
                }
            ]
        }
        result = bank_comparison(snapshot, expected)
        self.assertEqual(result["checked"], 2)
        self.assertEqual(result["correct"], 1)
        self.assertTrue(result["differences"])

    def test_automatic_service_date_is_not_treated_as_proven_issue_date(self):
        invoice = {
            "id": "synthetic",
            "reviewStatus": "AUTOMATIC",
            "total": "7.00",
            "issuedOn": "2020-01-01",
        }
        snapshot = {
            "documents": [
                {
                    "metadata": {"filename": "sample.pdf"},
                    "records": {"invoices": [invoice], "movements": []},
                }
            ],
            "jobs": [],
            "assistant/executions": [],
            "allocations": [],
        }
        truth = {
            "bankMovements": {},
            "rows": [
                {
                    "document": "sample.pdf",
                    "amount": "7.00",
                    "documentDate": "2020-01-01",
                    "dateKind": "service",
                }
            ],
        }
        result = application_summary(snapshot, truth)
        self.assertEqual(len(result["automaticFinancialViolations"]), 1)


if __name__ == "__main__":
    unittest.main()
