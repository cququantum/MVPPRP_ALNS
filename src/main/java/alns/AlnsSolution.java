package alns;

import instance.Instance;

import java.util.ArrayList;

public final class AlnsSolution {
    public final boolean[][] z;
    public final double[][] q;
    public final double[][] supplierInventory;
    public final ArrayList<Route>[] routes;
    public final double[] y;
    public final double[] p;
    public final double[] p0;
    public final double[] i0;

    public double objective = Double.NaN;
    public double routeCost = Double.NaN;
    public double supplierHoldingCost = Double.NaN;
    public double productionCost = Double.NaN;
    public boolean feasible = false;
    public String infeasibilityReason = "";

    @SuppressWarnings("unchecked")
    public AlnsSolution(Instance ins) {
        this.z = new boolean[ins.n + 1][ins.l + 1];
        this.q = new double[ins.n + 1][ins.l + 1];
        this.supplierInventory = new double[ins.n + 1][ins.l + 1];
        this.routes = (ArrayList<Route>[]) new ArrayList[ins.l + 1];
        for (int t = 1; t <= ins.l; t++) {
            this.routes[t] = new ArrayList<Route>();
        }
        this.y = new double[ins.l + 1];
        this.p = new double[ins.l + 1];
        this.p0 = new double[ins.l + 1];
        this.i0 = new double[ins.l + 1];
    }

    public AlnsSolution deepCopy(Instance ins) {
        AlnsSolution copy = new AlnsSolution(ins);
        for (int i = 1; i <= ins.n; i++) {
            System.arraycopy(this.z[i], 0, copy.z[i], 0, this.z[i].length);
            System.arraycopy(this.q[i], 0, copy.q[i], 0, this.q[i].length);
            System.arraycopy(this.supplierInventory[i], 0, copy.supplierInventory[i], 0, this.supplierInventory[i].length);
        }
        for (int t = 1; t <= ins.l; t++) {
            for (Route route : this.routes[t]) {
                copy.routes[t].add(route.copy());
            }
        }
        System.arraycopy(this.y, 0, copy.y, 0, this.y.length);
        System.arraycopy(this.p, 0, copy.p, 0, this.p.length);
        System.arraycopy(this.p0, 0, copy.p0, 0, this.p0.length);
        System.arraycopy(this.i0, 0, copy.i0, 0, this.i0.length);
        copy.objective = this.objective;
        copy.routeCost = this.routeCost;
        copy.supplierHoldingCost = this.supplierHoldingCost;
        copy.productionCost = this.productionCost;
        copy.feasible = this.feasible;
        copy.infeasibilityReason = this.infeasibilityReason;
        return copy;
    }

    public void clearPeriod(int t) {
        routes[t].clear();
        for (int i = 1; i < z.length; i++) {
            z[i][t] = false;
        }
    }

    public boolean isVisited(int customer, int period) {
        return z[customer][period];
    }

    public int routeCount(int t) {
        return routes[t].size();
    }

    public int findRouteIndexContaining(int t, int customer) {
        for (int r = 0; r < routes[t].size(); r++) {
            if (routes[t].get(r).customers.contains(customer)) {
                return r;
            }
        }
        return -1;
    }

    public int findCustomerPosition(int t, int routeIdx, int customer) {
        ArrayList<Integer> customers = routes[t].get(routeIdx).customers;
        for (int pos = 0; pos < customers.size(); pos++) {
            if (customers.get(pos).intValue() == customer) {
                return pos;
            }
        }
        return -1;
    }

    public int previousVisit(int customer, int periodExclusive) {
        for (int t = periodExclusive - 1; t >= 1; t--) {
            if (z[customer][t]) {
                return t;
            }
        }
        return 0;
    }

    public int nextVisit(Instance ins, int customer, int periodExclusive) {
        for (int t = periodExclusive + 1; t <= ins.l; t++) {
            if (z[customer][t]) {
                return t;
            }
        }
        return ins.l + 1;
    }

    public int countVisitsForCustomer(Instance ins, int customer) {
        int count = 0;
        for (int t = 1; t <= ins.l; t++) {
            if (z[customer][t]) {
                count++;
            }
        }
        return count;
    }

    public void removeVisit(int customer, int period) {
        z[customer][period] = false;
        int routeIdx = findRouteIndexContaining(period, customer);
        if (routeIdx >= 0) {
            Route route = routes[period].get(routeIdx);
            route.customers.remove(Integer.valueOf(customer));
            route.load = Math.max(0.0, route.load - Math.max(0.0, q[customer][period]));
            if (route.customers.isEmpty()) {
                routes[period].remove(routeIdx);
            }
        }
    }

    public void insertVisitAt(int customer, int period, int routeIdx, int position) {
        insertVisitAt(customer, period, routeIdx, position, Math.max(0.0, q[customer][period]));
    }

    public void insertVisitAt(int customer, int period, int routeIdx, int position, double demand) {
        z[customer][period] = true;
        q[customer][period] = demand;
        if (routeIdx == routes[period].size()) {
            Route route = new Route();
            route.customers.add(customer);
            route.load = demand;
            routes[period].add(route);
            return;
        }
        Route route = routes[period].get(routeIdx);
        route.customers.add(position, customer);
        route.load += demand;
    }

    public void setProductionPlan(ProductionReoptimizer.Result r) {
        for (int t = 1; t < y.length; t++) {
            y[t] = r.y[t];
            p[t] = r.p[t];
            p0[t] = r.p0[t];
            i0[t] = r.i0[t];
        }
    }
}
