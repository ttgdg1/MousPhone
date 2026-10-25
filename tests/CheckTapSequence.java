package io.pocketpad.remote;

public final class CheckTapSequence {
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        TapSequence taps = new TapSequence();
        check(!taps.isSecondTap(1_000, 20, 30, 48), "first tap is not second tap");
        taps.recordTap(1_100, 20, 30);
        check(taps.isSecondTap(1_260, 26, 35, 48), "quick nearby second tap drags");
        check(!taps.isSecondTap(1_330, 26, 35, 48), "third press must not drag");
        taps.recordTap(2_000, 20, 30);
        check(!taps.isSecondTap(2_400, 20, 30, 48), "slow tap must not drag");
        taps.recordTap(3_000, 20, 30);
        check(!taps.isSecondTap(3_200, 100, 100, 48), "far tap must not drag");
        taps.recordTap(4_000, 20, 30);
        taps.clear();
        check(!taps.isSecondTap(4_100, 20, 30, 48), "cancel removes double tap state");
        System.out.println("OK: double tap recognition, timeout, distance, cancellation");
    }
}
