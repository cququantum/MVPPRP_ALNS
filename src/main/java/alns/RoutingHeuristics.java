package alns;

import instance.Instance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class RoutingHeuristics {
    private RoutingHeuristics() {
    }

    public static final class Insertion {
        public final int routeIdx;
        public final int position;
        public final double deltaCost;

        public Insertion(int routeIdx, int position, double deltaCost) {
            this.routeIdx = routeIdx;
            this.position = position;
            this.deltaCost = deltaCost;
        }
    }

    private static final class Saving {
        final int i;
        final int j;
        final double saving;

        Saving(int i, int j, double saving) {
            this.i = i;
            this.j = j;
            this.saving = saving;
        }
    }

    public static boolean rebuildPeriodRoutes(Instance ins, AlnsSolution sol, int t, SolutionEvaluator evaluator) {
        SolutionEvaluator.EvaluationResult structure = evaluator.evaluatePlan(ins, sol);
        if (!structure.structureFeasible) {
            return false;
        }

        ArrayList<Integer> customers = new ArrayList<Integer>();
        for (int i = 1; i <= ins.n; i++) {
            if (sol.z[i][t]) {
                if (sol.q[i][t] > ins.Q + SolutionEvaluator.EPS) {
                    return false;
                }
                customers.add(i);
            }
        }

        sol.routes[t].clear();
        if (customers.isEmpty()) {
            return true;
        }

        ArrayList<Route> routes = new ArrayList<Route>();
        for (Integer customer : customers) {
            Route route = new Route();
            route.customers.add(customer);
            route.load = sol.q[customer][t];
            routes.add(route);
        }

        ArrayList<Saving> savings = new ArrayList<Saving>();
        for (int a = 0; a < customers.size(); a++) {
            for (int b = a + 1; b < customers.size(); b++) {
                int i = customers.get(a).intValue();
                int j = customers.get(b).intValue();
                double saving = ins.c[0][i] + ins.c[j][ins.n + 1] - ins.c[i][j];
                savings.add(new Saving(i, j, saving));
                savings.add(new Saving(j, i, ins.c[0][j] + ins.c[i][ins.n + 1] - ins.c[j][i]));
            }
        }
        Collections.sort(savings, new Comparator<Saving>() {
            @Override
            public int compare(Saving a, Saving b) {
                return Double.compare(b.saving, a.saving);
            }
        });

        for (Saving saving : savings) {
            int leftIdx = findRouteEndingWith(routes, saving.i);
            int rightIdx = findRouteStartingWith(routes, saving.j);
            if (leftIdx < 0 || rightIdx < 0 || leftIdx == rightIdx) {
                continue;
            }
            Route left = routes.get(leftIdx);
            Route right = routes.get(rightIdx);
            if (left.load + right.load > ins.Q + SolutionEvaluator.EPS) {
                continue;
            }
            left.customers.addAll(right.customers);
            left.load += right.load;
            routes.remove(rightIdx);
        }

        if (routes.size() > ins.K) {
            ArrayList<Route> rebuilt = greedyPack(ins, sol, t, customers);
            if (rebuilt == null) {
                return false;
            }
            routes = rebuilt;
        }

        sol.routes[t].clear();
        sol.routes[t].addAll(routes);
        return true;
    }

    private static ArrayList<Route> greedyPack(Instance ins, AlnsSolution sol, int t, ArrayList<Integer> customers) {
        ArrayList<Integer> sorted = new ArrayList<Integer>(customers);
        Collections.sort(sorted, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(sol.q[b.intValue()][t], sol.q[a.intValue()][t]);
            }
        });

        ArrayList<Route> routes = new ArrayList<Route>();
        for (Integer customer : sorted) {
            Insertion best = null;
            int bestRouteIdx = -1;
            for (int routeIdx = 0; routeIdx <= routes.size(); routeIdx++) {
                if (routeIdx == routes.size() && routes.size() >= ins.K) {
                    continue;
                }
                Route route;
                if (routeIdx == routes.size()) {
                    route = new Route();
                } else {
                    route = routes.get(routeIdx);
                }
                if (route.load + sol.q[customer.intValue()][t] > ins.Q + SolutionEvaluator.EPS) {
                    continue;
                }
                int positionCount = route.customers.isEmpty() ? 1 : route.customers.size() + 1;
                for (int pos = 0; pos < positionCount; pos++) {
                    double delta = insertionDelta(ins, route, customer.intValue(), pos);
                    if (best == null || delta < best.deltaCost - SolutionEvaluator.EPS) {
                        best = new Insertion(routeIdx, pos, delta);
                        bestRouteIdx = routeIdx;
                    }
                }
            }
            if (best == null) {
                return null;
            }
            if (bestRouteIdx == routes.size()) {
                Route route = new Route();
                route.customers.add(customer);
                route.load = sol.q[customer.intValue()][t];
                routes.add(route);
            } else {
                Route route = routes.get(bestRouteIdx);
                route.customers.add(best.position, customer);
                route.load += sol.q[customer.intValue()][t];
            }
        }
        return routes;
    }

    public static Insertion findBestInsertion(Instance ins, AlnsSolution sol, int customer, int t) {
        if (sol.z[customer][t]) {
            return null;
        }
        Insertion best = null;
        double demand = sol.q[customer][t];
        return findBestInsertion(ins, sol, customer, t, demand);
    }

    public static Insertion findBestInsertion(Instance ins, AlnsSolution sol, int customer, int t, double demand) {
        Insertion best = null;
        for (int routeIdx = 0; routeIdx <= sol.routes[t].size(); routeIdx++) {
            if (routeIdx == sol.routes[t].size() && sol.routes[t].size() >= ins.K) {
                continue;
            }
            Route route;
            if (routeIdx == sol.routes[t].size()) {
                route = new Route();
            } else {
                route = sol.routes[t].get(routeIdx);
            }
            if (route.load + demand > ins.Q + SolutionEvaluator.EPS) {
                continue;
            }
            int positionCount = route.customers.isEmpty() ? 1 : route.customers.size() + 1;
            for (int pos = 0; pos < positionCount; pos++) {
                double delta = insertionDelta(ins, route, customer, pos);
                if (best == null || delta < best.deltaCost - SolutionEvaluator.EPS) {
                    best = new Insertion(routeIdx, pos, delta);
                }
            }
        }
        return best;
    }

    public static double routeCost(Instance ins, Route route) {
        if (route.customers.isEmpty()) {
            return 0.0;
        }
        double cost = 0.0;
        int prev = 0;
        for (Integer customer : route.customers) {
            cost += ins.c[prev][customer.intValue()];
            prev = customer.intValue();
        }
        cost += ins.c[prev][ins.n + 1];
        return cost;
    }

    public static double insertionDelta(Instance ins, Route route, int customer, int position) {
        if (route.customers.isEmpty()) {
            return ins.c[0][customer] + ins.c[customer][ins.n + 1];
        }
        int prev = (position == 0) ? 0 : route.customers.get(position - 1).intValue();
        int next = (position == route.customers.size()) ? (ins.n + 1) : route.customers.get(position).intValue();
        return ins.c[prev][customer] + ins.c[customer][next] - ins.c[prev][next];
    }

    public static double removalDelta(Instance ins, Route route, int position) {
        int customer = route.customers.get(position).intValue();
        int prev = (position == 0) ? 0 : route.customers.get(position - 1).intValue();
        int next = (position == route.customers.size() - 1) ? (ins.n + 1) : route.customers.get(position + 1).intValue();
        return ins.c[prev][next] - ins.c[prev][customer] - ins.c[customer][next];
    }

    private static int findRouteEndingWith(ArrayList<Route> routes, int customer) {
        for (int idx = 0; idx < routes.size(); idx++) {
            Route route = routes.get(idx);
            if (!route.customers.isEmpty() && route.customers.get(route.customers.size() - 1).intValue() == customer) {
                return idx;
            }
        }
        return -1;
    }

    private static int findRouteStartingWith(ArrayList<Route> routes, int customer) {
        for (int idx = 0; idx < routes.size(); idx++) {
            Route route = routes.get(idx);
            if (!route.customers.isEmpty() && route.customers.get(0).intValue() == customer) {
                return idx;
            }
        }
        return -1;
    }
}
