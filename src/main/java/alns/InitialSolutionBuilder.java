package alns;

import instance.Instance;

public final class InitialSolutionBuilder {
    private static final int MAX_REPAIR_ROUNDS = 200;

    private final SolutionEvaluator evaluator;
    private final ProductionReoptimizer productionReoptimizer;

    public InitialSolutionBuilder(SolutionEvaluator evaluator, ProductionReoptimizer productionReoptimizer) {
        this.evaluator = evaluator;
        this.productionReoptimizer = productionReoptimizer;
    }

    public AlnsSolution build(Instance ins, AlnsConfig config) {
        AlnsSolution solution = new AlnsSolution(ins);
        if (!buildDeadlineAwareVisitPlan(ins, solution)) {
            buildAllVisitsFallback(ins, solution);
        }
        if (!buildRoutesAndProduction(ins, solution, config.timeLimitSec)) {
            buildAllVisitsFallback(ins, solution);
            if (!buildRoutesAndProduction(ins, solution, config.timeLimitSec)) {
                throw new IllegalStateException("Failed to build an initial feasible ALNS solution.");
            }
        }
        return solution;
    }

    private boolean buildDeadlineAwareVisitPlan(Instance ins, AlnsSolution solution) {
        for (int t = 1; t <= ins.l; t++) {
            for (int i = 1; i <= ins.n; i++) {
                int prev = solution.previousVisit(i, t);
                if (ins.mu[i][prev] == t) {
                    solution.z[i][t] = true;
                }
            }

            SolutionEvaluator.EvaluationResult state = evaluator.evaluatePlan(ins, solution);
            if (!prefixLoadsWithinFleet(ins, state, t)
                    || (state.firstSupplierOverflowPeriod >= 0 && state.firstSupplierOverflowPeriod <= t)) {
                return false;
            }

            while (state.cumulativeRawAvailability[t] + SolutionEvaluator.EPS < state.requiredRawLowerBound[t]) {
                int bestCustomer = -1;
                double bestGain = -1.0;
                for (int i = 1; i <= ins.n; i++) {
                    if (solution.z[i][t]) {
                        continue;
                    }
                    AlnsSolution candidate = solution.deepCopy(ins);
                    candidate.z[i][t] = true;
                    SolutionEvaluator.EvaluationResult candidateState = evaluator.evaluatePlan(ins, candidate);
                    if (!prefixLoadsWithinFleet(ins, candidateState, t)
                            || (candidateState.firstSupplierOverflowPeriod >= 0 && candidateState.firstSupplierOverflowPeriod <= t)) {
                        continue;
                    }
                    double gain = candidateState.cumulativeRawAvailability[t] - state.cumulativeRawAvailability[t];
                    if (gain > bestGain + SolutionEvaluator.EPS) {
                        bestGain = gain;
                        bestCustomer = i;
                    }
                }
                if (bestCustomer < 0) {
                    return false;
                }
                solution.z[bestCustomer][t] = true;
                state = evaluator.evaluatePlan(ins, solution);
            }
        }

        SolutionEvaluator.EvaluationResult finalState = evaluator.evaluatePlan(ins, solution);
        return finalState.firstSupplierOverflowPeriod < 0
                && finalState.firstRawShortagePeriod < 0
                && allPeriodLoadsWithinFleet(ins, finalState);
    }

    private boolean buildRoutesAndProduction(Instance ins, AlnsSolution solution, double timeLimitSec) {
        for (int t = 1; t <= ins.l; t++) {
            if (!RoutingHeuristics.rebuildPeriodRoutes(ins, solution, t, evaluator)) {
                return false;
            }
        }
        SolutionEvaluator.EvaluationResult structure = evaluator.evaluateStructure(ins, solution);
        if (!structure.structureFeasible || structure.firstRawShortagePeriod >= 0) {
            return false;
        }
        ProductionReoptimizer.Result prod = productionReoptimizer.optimize(ins, solution, timeLimitSec);
        if (!prod.feasible) {
            return false;
        }
        solution.setProductionPlan(prod);
        return evaluator.evaluateFull(ins, solution).feasible;
    }

    private void buildAllVisitsFallback(Instance ins, AlnsSolution solution) {
        for (int i = 1; i <= ins.n; i++) {
            for (int t = 1; t <= ins.l; t++) {
                solution.z[i][t] = true;
            }
        }
    }

    private boolean allPeriodLoadsWithinFleet(Instance ins, SolutionEvaluator.EvaluationResult structure) {
        for (int t = 1; t <= ins.l; t++) {
            if (structure.periodLoads[t] > ins.K * ins.Q + SolutionEvaluator.EPS) {
                return false;
            }
        }
        return true;
    }

    private boolean prefixLoadsWithinFleet(Instance ins, SolutionEvaluator.EvaluationResult structure, int lastPeriod) {
        for (int t = 1; t <= lastPeriod; t++) {
            if (structure.periodLoads[t] > ins.K * ins.Q + SolutionEvaluator.EPS) {
                return false;
            }
        }
        return true;
    }
}
