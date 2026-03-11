package alns;

import java.util.Objects;

public final class Visit {
    public final int customer;
    public final int period;

    public Visit(int customer, int period) {
        this.customer = customer;
        this.period = period;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Visit)) return false;
        Visit visit = (Visit) o;
        return customer == visit.customer && period == visit.period;
    }

    @Override
    public int hashCode() {
        return Objects.hash(customer, period);
    }
}
