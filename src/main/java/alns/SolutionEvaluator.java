package alns;

import instance.Instance;

public final class SolutionEvaluator {
    public static final double EPS = 1e-6;

    public static final class EvaluationResult {
        public final boolean structureFeasible;
        public final boolean feasible;
        public final String reason;
        public final double routeCost;
        public final double supplierHoldingCost;
        public final double productionCost;
        public final double objective;
        public final double[] periodLoads;
        public final int firstSupplierOverflowPeriod;
        public final int firstRawShortagePeriod;
        public final double[] cumulativeRawAvailability;
        public final double[] requiredRawLowerBound;

        EvaluationResult(
                boolean structureFeasible,
                boolean feasible,
                String reason,
                double routeCost,
                double supplierHoldingCost,
                double productionCost,
                double objective,
                double[] periodLoads,
                int firstSupplierOverflowPeriod,
                int firstRawShortagePeriod,
                double[] cumulativeRawAvailability,
                double[] requiredRawLowerBound
        ) {
            this.structureFeasible = structureFeasible;
            this.feasible = feasible;
            this.reason = reason;
            this.routeCost = routeCost;
            this.supplierHoldingCost = supplierHoldingCost;
            this.productionCost = productionCost;
            this.objective = objective;
            this.periodLoads = periodLoads;
            this.firstSupplierOverflowPeriod = firstSupplierOverflowPeriod;
            this.firstRawShortagePeriod = firstRawShortagePeriod;
            this.cumulativeRawAvailability = cumulativeRawAvailability;
            this.requiredRawLowerBound = requiredRawLowerBound;
        }
    }

    public EvaluationResult evaluatePlan(Instance ins, AlnsSolution solution) {
        return evaluateInternal(ins, solution, false);
    }

    public EvaluationResult evaluateStructure(Instance ins, AlnsSolution solution) {
        return evaluateInternal(ins, solution, true);
    }

    private EvaluationResult evaluateInternal(Instance ins, AlnsSolution solution, boolean checkRoutes) {
        for (int i = 1; i <= ins.n; i++) {
            for (int t = 1; t <= ins.l; t++) {
                solution.q[i][t] = 0.0;
                solution.supplierInventory[i][t] = 0.0;
            }
        }

        double supplierHoldingCost = 0.0;
        int firstSupplierOverflow = -1;
        double[] periodLoads = new double[ins.l + 1];

        for (int i = 1; i <= ins.n; i++) {
            double invEnd = ins.Ii0[i];
            for (int t = 1; t <= ins.l; t++) {
                double beforePickup = invEnd + ins.s[i][t];
                if (beforePickup > ins.Li[i] + EPS && firstSupplierOverflow < 0) {
                    firstSupplierOverflow = t;
                }
                if (solution.z[i][t]) {
                    solution.q[i][t] = beforePickup;
                    solution.supplierInventory[i][t] = 0.0;
                    invEnd = 0.0;
                } else {
                    solution.q[i][t] = 0.0;
                    solution.supplierInventory[i][t] = beforePickup;
                    invEnd = beforePickup;
                    supplierHoldingCost += ins.hi[i] * invEnd;
                }
                periodLoads[t] += solution.q[i][t];
            }
        }

        boolean[][] seen = new boolean[ins.n + 1][ins.l + 1];
        double routeCost = 0.0;
        String reason = null;

        for (int t = 1; t <= ins.l; t++) {
            if (checkRoutes && solution.routes[t].size() > ins.K && reason == null) {
                reason = "too many routes at t=" + t;
            }
            for (Route route : solution.routes[t]) {
                double load = 0.0;
                int prev = 0;
                for (Integer customerObj : route.customers) {
                    int customer = customerObj.intValue();
                    if (customer < 1 || customer > ins.n) {
                        if (reason == null) {
                            reason = "invalid customer id on route";
                        }
                        continue;
                    }
                    if (checkRoutes && !solution.z[customer][t] && reason == null) {
                        reason = "route contains unplanned visit i=" + customer + ", t=" + t;
                    }
                    if (checkRoutes && seen[customer][t] && reason == null) {
                        reason = "duplicate route visit i=" + customer + ", t=" + t;
                    }
                    seen[customer][t] = true;
                    load += solution.q[customer][t];
                    routeCost += ins.c[prev][customer];
                    prev = customer;
                }
                if (!route.customers.isEmpty()) {
                    routeCost += ins.c[prev][ins.n + 1];
                }
                route.load = load;
                if (checkRoutes && load > ins.Q + EPS && reason == null) {
                    reason = "route capacity exceeded at t=" + t;
                }
            }

            if (checkRoutes) {
                for (int i = 1; i <= ins.n; i++) {
                    if (solution.z[i][t] && !seen[i][t] && reason == null) {
                    reason = "planned visit missing on route i=" + i + ", t=" + t;
                }
                }
            }
        }

        double[] rawAvail = new double[ins.l + 1];
        double[] rawRequired = new double[ins.l + 1];
        double cumulativeCollected = ins.I00;
        double cumulativeDemand = 0.0;
        int firstRawShortage = -1;
        for (int t = 1; t <= ins.l; t++) {
            cumulativeCollected += periodLoads[t];
            cumulativeDemand += ins.dt[t];
            rawAvail[t] = cumulativeCollected;
            rawRequired[t] = ins.k * Math.max(0.0, cumulativeDemand - ins.P00);
            if (cumulativeCollected + EPS < rawRequired[t] && firstRawShortage < 0) {
                firstRawShortage = t;
            }
            if (periodLoads[t] > ins.K * ins.Q + EPS && reason == null) {
                reason = "period load exceeds fleet capacity at t=" + t;
            }
        }

        boolean structureFeasible = (reason == null && firstSupplierOverflow < 0);
        solution.routeCost = routeCost;
        solution.supplierHoldingCost = supplierHoldingCost;
        if (!structureFeasible) {
            solution.feasible = false;
            solution.infeasibilityReason = (reason != null) ? reason : ("supplier overflow at t=" + firstSupplierOverflow);
            solution.objective = Double.NaN;
        }

        return new EvaluationResult(
                structureFeasible,
                false,
                (reason != null) ? reason : ((firstSupplierOverflow >= 0) ? ("supplier overflow at t=" + firstSupplierOverflow) : ""),
                routeCost,
                supplierHoldingCost,
                Double.NaN,
                Double.NaN,
                periodLoads,
                firstSupplierOverflow,
                firstRawShortage,
                rawAvail,
                rawRequired
        );
    }

    public EvaluationResult evaluateFull(Instance ins, AlnsSolution solution) {
        EvaluationResult structure = evaluateStructure(ins, solution);
        if (!structure.structureFeasible) {
            return structure;
        }

        double productionCost = 0.0;
        String reason = null;
        for (int t = 1; t <= ins.l; t++) {
            if (solution.y[t] < -EPS || solution.p[t] < -EPS || solution.p0[t] < -EPS || solution.i0[t] < -EPS) {
                reason = "negative production variable at t=" + t;
                break;
            }
            if (solution.p[t] > ins.C * solution.y[t] + EPS) {
                reason = "production capacity violated at t=" + t;
                break;
            }
            if (solution.p0[t] > ins.Lp + EPS) {
                reason = "finished inventory capacity violated at t=" + t;
                break;
            }
            if (solution.i0[t] > ins.L0 + EPS) {
                reason = "raw inventory capacity violated at t=" + t;
                break;
            }

            double leftFinished = ((t == 1) ? ins.P00 : solution.p0[t - 1]) + solution.p[t];
            double rightFinished = ins.dt[t] + solution.p0[t];
            if (Math.abs(leftFinished - rightFinished) > 1e-4) {
                reason = "finished balance violated at t=" + t;
                break;
            }

            double pickup = 0.0;
            for (int i = 1; i <= ins.n; i++) {
                pickup += solution.q[i][t];
            }
            double leftRaw = ((t == 1) ? ins.I00 : solution.i0[t - 1]) + pickup;
            double rightRaw = ins.k * solution.p[t] + solution.i0[t];
            if (Math.abs(leftRaw - rightRaw) > 1e-4) {
                reason = "raw balance violated at t=" + t;
                break;
            }

            productionCost += ins.u * solution.p[t]
                    + ins.f * solution.y[t]
                    + ins.h0 * solution.i0[t]
                    + ins.hp * solution.p0[t];
        }

        if (reason == null && structure.firstRawShortagePeriod >= 0) {
            reason = "raw shortage lower bound at t=" + structure.firstRawShortagePeriod;
        }

        if (reason != null) {
            solution.feasible = false;
            solution.infeasibilityReason = reason;
            solution.productionCost = Double.NaN;
            solution.objective = Double.NaN;
            return new EvaluationResult(
                    true,
                    false,
                    reason,
                    structure.routeCost,
                    structure.supplierHoldingCost,
                    Double.NaN,
                    Double.NaN,
                    structure.periodLoads,
                    structure.firstSupplierOverflowPeriod,
                    structure.firstRawShortagePeriod,
                    structure.cumulativeRawAvailability,
                    structure.requiredRawLowerBound
            );
        }

        double objective = structure.routeCost + structure.supplierHoldingCost + productionCost;
        solution.feasible = true;
        solution.infeasibilityReason = "";
        solution.productionCost = productionCost;
        solution.objective = objective;
        return new EvaluationResult(
                true,
                true,
                "",
                structure.routeCost,
                structure.supplierHoldingCost,
                productionCost,
                objective,
                structure.periodLoads,
                structure.firstSupplierOverflowPeriod,
                structure.firstRawShortagePeriod,
                structure.cumulativeRawAvailability,
                structure.requiredRawLowerBound
        );
    }
}
