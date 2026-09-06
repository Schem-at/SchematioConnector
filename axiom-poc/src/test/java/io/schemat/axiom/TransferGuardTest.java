package io.schemat.axiom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferGuardTest {
    @Test void lateDownloadCannotOverwriteAPlayersNewCopy() {
        var guard = new TransferGuard(); var world = new Object(); var clipboard = new Object();
        var request = guard.begin(world, clipboard);
        assertNull(guard.rejection(request, world, clipboard, false, true));
        assertNotNull(guard.rejection(request, world, new Object(), false, true));
    }
    @Test void cancelAndNewRequestsInvalidateCompletions() {
        var guard = new TransferGuard(); var world = new Object();
        var first = guard.begin(world, null);
        guard.cancel();
        assertFalse(guard.isCurrent(first));
        var second = guard.begin(world, null);
        assertFalse(guard.isCurrent(first));
        assertNull(guard.rejection(second, world, null, false, true));
    }
    @Test void worldAndPermissionsAreRecheckedAtHandoff() {
        var guard = new TransferGuard(); var world = new Object(); var request = guard.begin(world, null);
        assertNotNull(guard.rejection(request, null, null, false, true));
        assertNotNull(guard.rejection(request, new Object(), null, false, true));
        assertNotNull(guard.rejection(request, world, null, false, false));
        assertNotNull(guard.rejection(request, world, null, true, true));
    }
}
