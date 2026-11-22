package app.photos

import org.junit.Assert.*
import org.junit.Test

class DeletionRulesTest {
    @Test fun emptyQueriesUnderLimitedAccessAreUnknown() {
        assertEquals(PhonePresence.UNKNOWN, DeletionRules.absentResult(false))
        assertEquals(PhonePresence.MISSING, DeletionRules.absentResult(true))
    }
    @Test fun lossOfPermissionIsNeverEvidenceOfDeletion() {
        assertFalse(DeletionRules.eligible(listOf(PhonePresence.UNKNOWN)))
        assertFalse(DeletionRules.eligible(listOf(PhonePresence.MISSING, PhonePresence.UNKNOWN)))
    }
    @Test fun sharedBackupWithActivePhoneItemMustBeKept() {
        assertFalse(DeletionRules.eligible(listOf(PhonePresence.TRASHED, PhonePresence.PRESENT)))
    }
    @Test fun onlyAbsentOrTrashedLinkedItemsAreReviewable() {
        assertTrue(DeletionRules.eligible(listOf(PhonePresence.MISSING, PhonePresence.TRASHED)))
        assertTrue(DeletionRules.eligible(listOf(PhonePresence.TRASHED)))
    }
    @Test fun unlinkedFileIsNotAnAutomaticReviewCandidate() {
        assertFalse(DeletionRules.eligible(emptyList()))
    }
}
