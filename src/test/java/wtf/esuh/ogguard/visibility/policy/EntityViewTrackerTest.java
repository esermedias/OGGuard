package wtf.esuh.ogguard.visibility.policy;

import wtf.esuh.ogguard.visibility.policy.EntityViewTracker.Transition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityViewTrackerTest {

    static final EntityViewTracker.HideRule BELOW_ZERO = (x, y, z, player) -> y < 0;

    @Test
    void spawnBelowZeroIsHidden() {
        EntityViewTracker<String> t = new EntityViewTracker<>(1);
        assertTrue(t.onSpawn(10, 0, -5, 0, false, "zombie", BELOW_ZERO));
        assertFalse(t.onSpawn(11, 0, 5, 0, false, "cow", BELOW_ZERO));
        assertTrue(t.isHidden(10));
        assertFalse(t.isHidden(11));
    }

    @Test
    void viewerIsNeverHiddenFromItself() {
        EntityViewTracker<String> t = new EntityViewTracker<>(7);
        assertFalse(t.onSpawn(7, 0, -30, 0, true, "self", BELOW_ZERO));
        assertEquals(Transition.STAY_VISIBLE, t.onAbsoluteMove(7, 0, -40, 0, BELOW_ZERO));
    }

    @Test
    void relativeMovesAccumulateAndTransition() {
        EntityViewTracker<String> t = new EntityViewTracker<>(1);
        t.onSpawn(5, 0, 1, 0, false, "pig", BELOW_ZERO);
        assertEquals(Transition.STAY_VISIBLE, t.onRelativeMove(5, 0, -0.5, 0, BELOW_ZERO));
        assertEquals(Transition.BECAME_HIDDEN, t.onRelativeMove(5, 0, -1.0, 0, BELOW_ZERO));
        assertEquals(Transition.STAY_HIDDEN, t.onRelativeMove(5, 0, -1.0, 0, BELOW_ZERO));
        assertEquals(Transition.BECAME_VISIBLE, t.onAbsoluteMove(5, 0, 3, 0, BELOW_ZERO));
        assertEquals(3.0, t.get(5).y());
    }

    @Test
    void unknownIdsAreForwarded() {
        EntityViewTracker<String> t = new EntityViewTracker<>(1);
        assertEquals(Transition.UNKNOWN, t.onAbsoluteMove(99, 0, -5, 0, BELOW_ZERO));
        assertFalse(t.isHidden(99));
    }

    @Test
    void revalidateReportsChangesAfterViewerStateChange() {
        EntityViewTracker<String> t = new EntityViewTracker<>(1);
        t.onSpawn(2, 0, -5, 0, false, "a", BELOW_ZERO);
        t.onSpawn(3, 0, 5, 0, false, "b", BELOW_ZERO);
        EntityViewTracker.Changes revealAll = t.revalidate((x, y, z, p) -> false);
        assertEquals(List.of(2), revealAll.shown());
        assertTrue(revealAll.hidden().isEmpty());
        EntityViewTracker.Changes hideAll = t.revalidate((x, y, z, p) -> true);
        assertEquals(2, hideAll.hidden().size());
    }

    @Test
    void removeAndClearForgetEntities() {
        EntityViewTracker<String> t = new EntityViewTracker<>(1);
        t.onSpawn(2, 0, -5, 0, false, "a", BELOW_ZERO);
        t.onRemove(2);
        assertFalse(t.isHidden(2));
        t.onSpawn(3, 0, -5, 0, false, "b", BELOW_ZERO);
        t.clear();
        assertEquals(0, t.size());
    }
}
