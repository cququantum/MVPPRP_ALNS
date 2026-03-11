package alns;

import java.util.ArrayList;

public final class DestroyContext {
    public final ArrayList<Visit> removedVisits = new ArrayList<Visit>();
    public final boolean[] destroyedPeriods;

    public DestroyContext(int l) {
        this.destroyedPeriods = new boolean[l + 1];
    }
}
