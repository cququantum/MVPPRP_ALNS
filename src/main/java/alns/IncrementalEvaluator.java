package alns;

import instance.Instance;

import java.util.ArrayList;

/**
 * Incremental (delta) evaluation utilities that avoid full deepCopy + evaluateFull.
 *
 * Design principle:
 *   For each candidate move, compute only the DELTA in cost/feasibility that the
 *   move would cause. Temporarily flip the z[][] flag where needed, compute the
 *   local effect, then flip back — all on the ORIGINAL solution, with no object
 *   allocation.
 */
public final class IncrementalEvaluator {

    private IncrementalEvaluator() {
    }

    public static double supplierHoldingCostForCustomer(Instance ins, boolean[] zRow, int customer) {
        double cost = 0.0;
        double invEnd = ins.Ii0[customer];
        for (int t = 1; t <= ins.l; t++) {
            double before = invEnd + ins.s[customer][t];
            double effectiveBefore = Math.min(before, ins.Li[customer]);
            if (zRow[t]) {
                invEnd = 0.0;
            } else {
                invEnd = effectiveBefore;
                cost += ins.hi[customer] * invEnd;
            }
        }
        return cost;
    }

    public static int firstOverflowForCustomer(Instance ins, boolean[] zRow, int customer) {
        double invEnd = ins.Ii0[customer];
        for (int t = 1; t <= ins.l; t++) {
            double before = invEnd + ins.s[customer][t];
            if (before > ins.Li[customer] + SolutionEvaluator.EPS) {
                return t;
            }
            invEnd = zRow[t] ? 0.0 : before;
        }
        return -1;
    }

    public static int firstOverflowFromPeriod(Instance ins, boolean[] zRow, int customer, int fromPeriod) {
        int full = firstOverflowForCustomer(ins, zRow, customer);
        if (full < 0 || full < fromPeriod) {
            return full;
        }
        return full;
    }

    public static final class RemovalDelta {
        public final double routeDelta;
        public final double holdingDelta;
        public final double totalDelta;
        public final boolean causesOverflow;

        RemovalDelta(double routeDelta, double holdingDelta, boolean causesOverflow) {
            this.routeDelta = routeDelta;
            this.holdingDelta = holdingDelta;
            this.totalDelta = routeDelta + holdingDelta;
            this.causesOverflow = causesOverflow;
        }
    }

    public static RemovalDelta visitRemovalDelta(Instance ins, AlnsSolution sol, int customer, int period) {
        double routeDelta = 0.0;
        int routeIdx = sol.findRouteIndexContaining(period, customer);
        if (routeIdx >= 0) {
            Route route = sol.routes[period].get(routeIdx);
            int pos = sol.findCustomerPosition(period, routeIdx, customer);
            if (pos >= 0) {
                if (route.customers.size() == 1) {
                    routeDelta = -(ins.c[0][customer] + ins.c[customer][ins.n + 1]);
                } else {
                    routeDelta = RoutingHeuristics.removalDelta(ins, route, pos);
                }
            }
        }

        double oldHolding = supplierHoldingCostForCustomer(ins, sol.z[customer], customer);
        sol.z[customer][period] = false;
        double newHolding = supplierHoldingCostForCustomer(ins, sol.z[customer], customer);
        boolean overflow = firstOverflowForCustomer(ins, sol.z[customer], customer) >= 0;
        sol.z[customer][period] = true;

        return new RemovalDelta(routeDelta, newHolding - oldHolding, overflow);
    }

    public static final class InsertionDelta {
        public final double routeDelta;
        public final double holdingDelta;
        public final double totalDelta;
        public final double pickupAmount;
        public final boolean causesOverflow;
        public final int firstOverflowAfter;

        InsertionDelta(double routeDelta, double holdingDelta, double pickupAmount,
                       boolean causesOverflow, int firstOverflowAfter) {
            this.routeDelta = routeDelta;
            this.holdingDelta = holdingDelta;
            this.totalDelta = routeDelta + holdingDelta;
            this.pickupAmount = pickupAmount;
            this.causesOverflow = causesOverflow;
            this.firstOverflowAfter = firstOverflowAfter;
        }
    }

    public static InsertionDelta visitInsertionDelta(Instance ins, AlnsSolution sol,
                                                     int customer, int period,
                                                     int routeIdx, int position,
                                                     double demand) {
        double routeDelta;
        if (routeIdx >= sol.routes[period].size()) {
            routeDelta = ins.c[0][customer] + ins.c[customer][ins.n + 1];
        } else {
            Route route = sol.routes[period].get(routeIdx);
            routeDelta = RoutingHeuristics.insertionDelta(ins, route, customer, position);
        }

        double oldHolding = supplierHoldingCostForCustomer(ins, sol.z[customer], customer);
        sol.z[customer][period] = true;
        double newHolding = supplierHoldingCostForCustomer(ins, sol.z[customer], customer);
        int overflow = firstOverflowForCustomer(ins, sol.z[customer], customer);
        sol.z[customer][period] = false;

        return new InsertionDelta(
                routeDelta,
                newHolding - oldHolding,
                demand,
                overflow >= 0,
                overflow
        );
    }

    public static double routingOnlyRelocateDelta(Instance ins, AlnsSolution sol, int t,
                                                  int fromRouteIdx, int fromPos,
                                                  int toRouteIdx, int insertPos) {
        Route from = sol.routes[t].get(fromRouteIdx);
        int customer = from.customers.get(fromPos).intValue();

        if (fromRouteIdx == toRouteIdx) {
            return sameRouteRelocateDelta(ins, from, fromPos, insertPos, customer);
        }

        Route to = sol.routes[t].get(toRouteIdx);

        double removalDelta;
        if (from.customers.size() == 1) {
            removalDelta = -(ins.c[0][customer] + ins.c[customer][ins.n + 1]);
        } else {
            removalDelta = RoutingHeuristics.removalDelta(ins, from, fromPos);
        }

        double insertionDelta = RoutingHeuristics.insertionDelta(ins, to, customer, insertPos);
        return removalDelta + insertionDelta;
    }

    private static double sameRouteRelocateDelta(Instance ins, Route route,
                                                 int fromPos, int insertPos,
                                                 int customer) {
        double oldCost = RoutingHeuristics.routeCost(ins, route);

        ArrayList<Integer> trial = new ArrayList<Integer>(route.customers);
        trial.remove(fromPos);
        int adjPos = (insertPos > fromPos) ? insertPos - 1 : insertPos;
        adjPos = Math.max(0, Math.min(adjPos, trial.size()));
        trial.add(adjPos, Integer.valueOf(customer));

        Route temp = new Route();
        temp.customers.addAll(trial);
        double newCost = RoutingHeuristics.routeCost(ins, temp);
        return newCost - oldCost;
    }

    public static double routingOnlySwapDelta(Instance ins, AlnsSolution sol, int t,
                                              int r1, int p1, int r2, int p2) {
        Route left = sol.routes[t].get(r1);
        Route right = sol.routes[t].get(r2);
        int c1 = left.customers.get(p1).intValue();
        int c2 = right.customers.get(p2).intValue();

        if (r1 == r2) {
            double oldCost = RoutingHeuristics.routeCost(ins, left);
            left.customers.set(p1, Integer.valueOf(c2));
            left.customers.set(p2, Integer.valueOf(c1));
            double newCost = RoutingHeuristics.routeCost(ins, left);
            left.customers.set(p1, Integer.valueOf(c1));
            left.customers.set(p2, Integer.valueOf(c2));
            return newCost - oldCost;
        }

        int leftPrev = (p1 == 0) ? 0 : left.customers.get(p1 - 1).intValue();
        int leftNext = (p1 == left.customers.size() - 1)
                ? (ins.n + 1) : left.customers.get(p1 + 1).intValue();

        int rightPrev = (p2 == 0) ? 0 : right.customers.get(p2 - 1).intValue();
        int rightNext = (p2 == right.customers.size() - 1)
                ? (ins.n + 1) : right.customers.get(p2 + 1).intValue();

        double oldLeft = ins.c[leftPrev][c1] + ins.c[c1][leftNext];
        double newLeft = ins.c[leftPrev][c2] + ins.c[c2][leftNext];
        double oldRight = ins.c[rightPrev][c2] + ins.c[c2][rightNext];
        double newRight = ins.c[rightPrev][c1] + ins.c[c1][rightNext];

        return (newLeft - oldLeft) + (newRight - oldRight);
    }

    public static double pickupAmountIfVisited(Instance ins, AlnsSolution sol, int customer, int period) {
        int prev = sol.previousVisit(customer, period);
        return ins.g(customer, prev, period);
    }
}
