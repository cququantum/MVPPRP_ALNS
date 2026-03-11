package alns;

import java.util.ArrayList;

public final class Route {
    public final ArrayList<Integer> customers = new ArrayList<Integer>();
    public double load;

    public Route copy() {
        Route x = new Route();
        x.customers.addAll(customers);
        x.load = load;
        return x;
    }
}
