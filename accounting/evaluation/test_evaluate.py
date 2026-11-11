"""The scorer must never confuse safe review with a silently accepted financial error."""
import unittest
from evaluate import score


class ScoringTest(unittest.TestCase):
    def test_wrong_accepted_total_is_a_financial_failure(self):
        result = score({"invoice": {"total": "12.00"}}, {"invoices": [{"total": "120.00", "reviewStatus": "AUTOMATIC"}]})
        self.assertEqual(result["silentFinancialErrors"], 1)

    def test_unknown_reviewed_total_reduces_coverage_without_silent_acceptance(self):
        result = score({"invoice": {"total": "12.00"}}, {"invoices": [{"total": None, "reviewStatus": "OPEN"}]})
        self.assertEqual(result["silentFinancialErrors"], 0)
        self.assertEqual(result["correct"], 0)

    def test_repeated_bank_rows_are_counted(self):
        row = {"bookedOn": "2026-01-01", "description": "Compra", "amount": "3.00", "direction": "DEBIT"}
        result = score({"movements": [row, row]}, {"movements": [dict(row, reviewStatus="AUTOMATIC")]})
        self.assertEqual(result["silentFinancialErrors"], 1)


if __name__ == "__main__":
    unittest.main()
