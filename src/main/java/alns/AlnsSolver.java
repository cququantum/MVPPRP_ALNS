package alns;

import instance.Instance;
import model.SolveResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;

public final class AlnsSolver {
    private static final double LARGE_REGRET = 1e9;
    private static final int DESTROY_COUNT = 6;
    private static final int REPAIR_COUNT = 3;
    private static final int MAX_REPAIR_STEPS = 200;
    private static final int MAX_PERIOD_REBUILD_ADDITIONS = 8;

    private enum TaskType { SUPPLIER, RAW }

    private static final class Task {
        final TaskType type;
        final int customer;
        final int deadline;

        Task(TaskType type, int customer, int deadline) {
            this.type = type;
            this.customer = customer;
            this.deadline = deadline;
        }
    }

    private static final class InsertionCandidate {
        final int customer;
        final int period;
        final int routeIdx;
        final int position;
        final double delta;
        final double gain;

        InsertionCandidate(int customer, int period, int routeIdx, int position, double delta, double gain) {
            this.customer = customer;
            this.period = period;
            this.routeIdx = routeIdx;
            this.position = position;
            this.delta = delta;
            this.gain = gain;
        }
    }

    private final AlnsConfig config;
    private final SolutionEvaluator evaluator;
    private final ProductionReoptimizer productionReoptimizer;
    private final InitialSolutionBuilder initialSolutionBuilder;

    public AlnsSolver(AlnsConfig config) {
        this.config = config;
        this.evaluator = new SolutionEvaluator();
        this.productionReoptimizer = new ProductionReoptimizer();
        this.initialSolutionBuilder = new InitialSolutionBuilder(evaluator, productionReoptimizer);
    }

    public SolveResult solve(Instance ins) {
        long startNs = System.nanoTime();
        long deadlineNs = startNs + (long) (config.timeLimitSec * 1_000_000_000L);
        Random random = new Random(config.seed);

        AlnsSolution current = initialSolutionBuilder.build(ins, config);
        AlnsSolution best = current.deepCopy(ins);

        double[] destroyWeights = initWeights(DESTROY_COUNT);
        double[] repairWeights = initWeights(REPAIR_COUNT);
        double[] destroyScores = new double[DESTROY_COUNT];
        double[] repairScores = new double[REPAIR_COUNT];
        int[] destroyUses = new int[DESTROY_COUNT];
        int[] repairUses = new int[REPAIR_COUNT];

        int iter = 0;
        int accepted = 0;
        int bestIter = 0;

        double initialTemperature = Math.max(1e-6, 0.05 * current.objective / Math.log(2.0));
        while (System.nanoTime() < deadlineNs) {
            iter++;
            int destroyIdx = roulette(destroyWeights, random);
            int repairIdx = roulette(repairWeights, random);
            destroyUses[destroyIdx]++;
            repairUses[repairIdx]++;

            double currentObjective = current.objective;
            AlnsSolution candidate = current.deepCopy(ins);
            DestroyContext ctx = applyDestroy(ins, candidate, destroyIdx, random);
            boolean repaired = applyRepair(ins, candidate, repairIdx, ctx, deadlineNs, random);
            if (!repaired) {
                continue;
            }
            if (!runProduction(candidate, ins, deadlineNs)) {
                continue;
            }
            if (!localSearch(ins, candidate, deadlineNs, random)) {
                continue;
            }

            if (!candidate.feasible || Double.isNaN(candidate.objective)) {
                continue;
            }

            boolean improvedCurrent = candidate.objective < currentObjective - SolutionEvaluator.EPS;
            boolean improvedBest = candidate.objective < best.objective - SolutionEvaluator.EPS;
            boolean accept = shouldAccept(currentObjective, candidate.objective, initialTemperature, startNs, deadlineNs, random);
            if (accept) {
                current = candidate;
                accepted++;
            }

            double score = 0.0;
            if (improvedBest) {
                best = candidate.deepCopy(ins);
                bestIter = iter;
                score = config.sigmaBest;
            } else if (improvedCurrent) {
                score = config.sigmaImprove;
            } else if (accept) {
                score = config.sigmaAccepted;
            }
            destroyScores[destroyIdx] += score;
            repairScores[repairIdx] += score;

            if (iter % config.segmentSize == 0) {
                updateWeights(destroyWeights, destroyScores, destroyUses);
                updateWeights(repairWeights, repairScores, repairUses);
                resetScores(destroyScores, destroyUses);
                resetScores(repairScores, repairUses);
            }
        }

        double sec = (System.nanoTime() - startNs) / 1_000_000_000.0;
        String status = String.format(
                Locale.US,
                "ALNS(seed=%d,iters=%d,accepted=%d,bestIter=%d)",
                config.seed,
                iter,
                accepted,
                bestIter
        );
        return new SolveResult("ALNS", best.feasible, false, status, best.objective, Double.NaN, Double.NaN, sec);
    }

    private boolean runProduction(AlnsSolution solution, Instance ins, long deadlineNs) {
        SolutionEvaluator.EvaluationResult structure = evaluator.evaluateStructure(ins, solution);
        if (!structure.structureFeasible || structure.firstRawShortagePeriod >= 0) {
            return false;
        }
        double remainingSec = Math.max(0.05, (deadlineNs - System.nanoTime()) / 1_000_000_000.0);
        ProductionReoptimizer.Result result = productionReoptimizer.optimize(ins, solution, remainingSec);
        if (!result.feasible) {
            return false;
        }
        solution.setProductionPlan(result);
        return evaluator.evaluateFull(ins, solution).feasible;
    }

    private boolean localSearch(Instance ins, AlnsSolution solution, long deadlineNs, Random random) {
        int moves = 0;
        boolean improved = true;
        while (improved && moves < config.moveBudgetPerIteration && System.nanoTime() < deadlineNs) {
            improved = false;
            if (applyTwoOpt(ins, solution)) {
                evaluator.evaluateFull(ins, solution);
                improved = true;
                moves++;
                continue;
            }
            if (applyIntraPeriodRelocate(ins, solution)) {
                improved = true;
                moves++;
                continue;
            }
            if (applyIntraPeriodSwap(ins, solution)) {
                improved = true;
                moves++;
                continue;
            }
            if (applyCrossPeriodRelocate(ins, solution, deadlineNs, random)) {
                improved = true;
                moves++;
            }
        }
        return solution.feasible;
    }

    private boolean applyTwoOpt(Instance ins, AlnsSolution solution) {
        for (int t = 1; t <= ins.l; t++) {
            for (Route route : solution.routes[t]) {
                if (route.customers.size() < 3) {
                    continue;
                }
                double baseCost = RoutingHeuristics.routeCost(ins, route);
                for (int i = 0; i < route.customers.size() - 2; i++) {
                    for (int k = i + 1; k < route.customers.size() - 1; k++) {
                        ArrayList<Integer> trial = new ArrayList<Integer>(route.customers);
                        reverse(trial, i, k);
                        Route candidate = new Route();
                        candidate.customers.addAll(trial);
                        double newCost = RoutingHeuristics.routeCost(ins, candidate);
                        if (newCost < baseCost - SolutionEvaluator.EPS) {
                            route.customers.clear();
                            route.customers.addAll(trial);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean applyIntraPeriodRelocate(Instance ins, AlnsSolution solution) {
        for (int t = 1; t <= ins.l; t++) {
            for (int fromIdx = 0; fromIdx < solution.routes[t].size(); fromIdx++) {
                Route from = solution.routes[t].get(fromIdx);
                for (int pos = 0; pos < from.customers.size(); pos++) {
                    int customer = from.customers.get(pos).intValue();
                    double demand = solution.q[customer][t];
                    for (int toIdx = 0; toIdx < solution.routes[t].size(); toIdx++) {
                        if (fromIdx == toIdx && from.customers.size() <= 1) {
                            continue;
                        }
                        Route to = solution.routes[t].get(toIdx);
                        if (fromIdx != toIdx && to.load + demand > ins.Q + SolutionEvaluator.EPS) {
                            continue;
                        }
                        int limit = to.customers.size() + 1;
                        for (int insertPos = 0; insertPos < limit; insertPos++) {
                            if (fromIdx == toIdx && (insertPos == pos || insertPos == pos + 1)) {
                                continue;
                            }
                            double delta = IncrementalEvaluator.routingOnlyRelocateDelta(
                                    ins, solution, t, fromIdx, pos, toIdx, insertPos);
                            if (delta < -SolutionEvaluator.EPS) {
                                relocateWithinPeriod(solution, t, fromIdx, pos, toIdx, insertPos);
                                evaluator.evaluateFull(ins, solution);
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean applyIntraPeriodSwap(Instance ins, AlnsSolution solution) {
        for (int t = 1; t <= ins.l; t++) {
            for (int r1 = 0; r1 < solution.routes[t].size(); r1++) {
                for (int r2 = r1; r2 < solution.routes[t].size(); r2++) {
                    Route left = solution.routes[t].get(r1);
                    Route right = solution.routes[t].get(r2);
                    for (int p1 = 0; p1 < left.customers.size(); p1++) {
                        for (int p2 = 0; p2 < right.customers.size(); p2++) {
                            if (r1 == r2 && p1 >= p2) {
                                continue;
                            }
                            int c1 = left.customers.get(p1).intValue();
                            int c2 = right.customers.get(p2).intValue();
                            if (r1 != r2) {
                                double newLeftLoad = left.load - solution.q[c1][t] + solution.q[c2][t];
                                double newRightLoad = right.load - solution.q[c2][t] + solution.q[c1][t];
                                if (newLeftLoad > ins.Q + SolutionEvaluator.EPS || newRightLoad > ins.Q + SolutionEvaluator.EPS) {
                                    continue;
                                }
                            }
                            double delta = IncrementalEvaluator.routingOnlySwapDelta(
                                    ins, solution, t, r1, p1, r2, p2);
                            if (delta < -SolutionEvaluator.EPS) {
                                left.customers.set(p1, Integer.valueOf(c2));
                                right.customers.set(p2, Integer.valueOf(c1));
                                evaluator.evaluateFull(ins, solution);
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean applyCrossPeriodRelocate(Instance ins, AlnsSolution solution, long deadlineNs, Random random) {
        ArrayList<Visit> visits = collectVisits(solution, ins);
        Collections.shuffle(visits, random);
        SolutionEvaluator.EvaluationResult base = evaluator.evaluateStructure(ins, solution);
        for (Visit visit : visits) {
            for (int targetPeriod = 1; targetPeriod <= ins.l; targetPeriod++) {
                if (targetPeriod == visit.period || solution.z[visit.customer][targetPeriod]) {
                    continue;
                }
                double demand = IncrementalEvaluator.pickupAmountIfVisited(
                        ins, solution, visit.customer, targetPeriod);
                if (base.periodLoads[targetPeriod] + demand > ins.K * ins.Q + SolutionEvaluator.EPS) {
                    continue;
                }

                solution.z[visit.customer][visit.period] = false;
                solution.z[visit.customer][targetPeriod] = true;
                int overflow = IncrementalEvaluator.firstOverflowForCustomer(
                        ins, solution.z[visit.customer], visit.customer);
                solution.z[visit.customer][visit.period] = true;
                solution.z[visit.customer][targetPeriod] = false;
                if (overflow >= 0) {
                    continue;
                }

                AlnsSolution candidate = solution.deepCopy(ins);
                candidate.removeVisit(visit.customer, visit.period);
                double newDemand = ins.g(visit.customer, candidate.previousVisit(visit.customer, targetPeriod), targetPeriod);
                RoutingHeuristics.Insertion insertion = RoutingHeuristics.findBestInsertion(ins, candidate, visit.customer, targetPeriod, newDemand);
                if (insertion == null) {
                    continue;
                }
                candidate.insertVisitAt(visit.customer, targetPeriod, insertion.routeIdx, insertion.position, newDemand);
                if (!runProduction(candidate, ins, deadlineNs)) {
                    continue;
                }
                if (candidate.objective < solution.objective - SolutionEvaluator.EPS) {
                    copyInto(solution, candidate, ins);
                    return true;
                }
            }
        }
        return false;
    }

    private DestroyContext applyDestroy(Instance ins, AlnsSolution solution, int destroyIdx, Random random) {
        switch (destroyIdx) {
            case 0:
                return destroyRandomVisits(ins, solution, random);
            case 1:
                return destroyWorstCostVisits(ins, solution, random);
            case 2:
                return destroyRelatedVisits(ins, solution, random);
            case 3:
                return destroyPeriods(ins, solution, random);
            case 4:
                return destroyRoute(ins, solution, random);
            default:
                return destroyProductionGuided(ins, solution, random);
        }
    }

    private boolean applyRepair(Instance ins, AlnsSolution solution, int repairIdx, DestroyContext ctx, long deadlineNs, Random random) {
        switch (repairIdx) {
            case 0:
                return repairGreedy(ins, solution, ctx, deadlineNs);
            case 1:
                return repairRegret(ins, solution, ctx, deadlineNs);
            default:
                return repairPeriodRebuild(ins, solution, ctx, deadlineNs);
        }
    }

    private DestroyContext destroyRandomVisits(Instance ins, AlnsSolution solution, Random random) {
        DestroyContext ctx = new DestroyContext(ins.l);
        ArrayList<Visit> visits = collectVisits(solution, ins);
        Collections.shuffle(visits, random);
        int removeCount = chooseDestroyCount(visits.size(), random);
        for (int idx = 0; idx < removeCount; idx++) {
            Visit visit = visits.get(idx);
            ctx.removedVisits.add(visit);
            solution.removeVisit(visit.customer, visit.period);
        }
        return ctx;
    }

    private DestroyContext destroyWorstCostVisits(Instance ins, AlnsSolution solution, Random random) {
        DestroyContext ctx = new DestroyContext(ins.l);
        ArrayList<Visit> visits = collectVisits(solution, ins);
        ArrayList<VisitScore> scored = new ArrayList<VisitScore>();
        for (Visit visit : visits) {
            IncrementalEvaluator.RemovalDelta rd = IncrementalEvaluator.visitRemovalDelta(
                    ins, solution, visit.customer, visit.period);
            if (rd.causesOverflow) {
                continue;
            }
            scored.add(new VisitScore(visit, rd.totalDelta));
        }
        Collections.sort(scored, new Comparator<VisitScore>() {
            @Override
            public int compare(VisitScore a, VisitScore b) {
                return Double.compare(a.score, b.score);
            }
        });
        int removeCount = chooseDestroyCount(scored.size(), random);
        for (int idx = 0; idx < removeCount; idx++) {
            Visit visit = scored.get(idx).visit;
            ctx.removedVisits.add(visit);
            solution.removeVisit(visit.customer, visit.period);
        }
        return ctx;
    }

    private DestroyContext destroyRelatedVisits(Instance ins, AlnsSolution solution, Random random) {
        DestroyContext ctx = new DestroyContext(ins.l);
        ArrayList<Visit> visits = collectVisits(solution, ins);
        if (visits.isEmpty()) {
            return ctx;
        }
        Visit seed = visits.get(random.nextInt(visits.size()));
        double maxDistance = maxDistance(ins);
        ArrayList<VisitScore> related = new ArrayList<VisitScore>();
        for (Visit visit : visits) {
            double dist = ins.c[seed.customer][visit.customer] / Math.max(1.0, maxDistance);
            double time = Math.abs(seed.period - visit.period) / (double) Math.max(1, ins.l - 1);
            double relatedness = 0.7 * dist + 0.3 * time;
            related.add(new VisitScore(visit, relatedness));
        }
        Collections.sort(related, new Comparator<VisitScore>() {
            @Override
            public int compare(VisitScore a, VisitScore b) {
                return Double.compare(a.score, b.score);
            }
        });
        int removeCount = chooseDestroyCount(related.size(), random);
        for (int idx = 0; idx < removeCount; idx++) {
            Visit visit = related.get(idx).visit;
            ctx.removedVisits.add(visit);
            solution.removeVisit(visit.customer, visit.period);
        }
        return ctx;
    }

    private DestroyContext destroyPeriods(Instance ins, AlnsSolution solution, Random random) {
        DestroyContext ctx = new DestroyContext(ins.l);
        int periodsToDestroy = 1 + random.nextInt(Math.min(2, ins.l));
        ArrayList<Integer> periods = new ArrayList<Integer>();
        for (int t = 1; t <= ins.l; t++) {
            periods.add(Integer.valueOf(t));
        }
        Collections.shuffle(periods, random);
        for (int idx = 0; idx < periodsToDestroy; idx++) {
            int t = periods.get(idx).intValue();
            ctx.destroyedPeriods[t] = true;
            for (int i = 1; i <= ins.n; i++) {
                if (solution.z[i][t]) {
                    ctx.removedVisits.add(new Visit(i, t));
                }
            }
            solution.clearPeriod(t);
        }
        return ctx;
    }

    private DestroyContext destroyRoute(Instance ins, AlnsSolution solution, Random random) {
        DestroyContext ctx = new DestroyContext(ins.l);
        ArrayList<Integer> candidatePeriods = new ArrayList<Integer>();
        for (int t = 1; t <= ins.l; t++) {
            if (!solution.routes[t].isEmpty()) {
                candidatePeriods.add(Integer.valueOf(t));
            }
        }
        if (candidatePeriods.isEmpty()) {
            return ctx;
        }
        int period = candidatePeriods.get(random.nextInt(candidatePeriods.size())).intValue();
        int routeIdx = random.nextInt(solution.routes[period].size());
        ArrayList<Integer> customers = new ArrayList<Integer>(solution.routes[period].get(routeIdx).customers);
        for (Integer customer : customers) {
            ctx.removedVisits.add(new Visit(customer.intValue(), period));
            solution.removeVisit(customer.intValue(), period);
        }
        return ctx;
    }

    private DestroyContext destroyProductionGuided(Instance ins, AlnsSolution solution, Random random) {
        DestroyContext ctx = new DestroyContext(ins.l);
        ArrayList<Integer> productionPeriods = new ArrayList<Integer>();
        for (int t = 1; t <= ins.l; t++) {
            if (solution.y[t] > SolutionEvaluator.EPS) {
                productionPeriods.add(Integer.valueOf(t));
            }
        }
        if (productionPeriods.isEmpty()) {
            return destroyRandomVisits(ins, solution, random);
        }

        Collections.shuffle(productionPeriods, random);
        int cancelCount = Math.min(1 + random.nextInt(2), productionPeriods.size());

        for (int idx = 0; idx < cancelCount; idx++) {
            int t = productionPeriods.get(idx).intValue();
            solution.y[t] = 0.0;
            solution.p[t] = 0.0;

            int neighbor = Math.max(1, Math.min(ins.l, t + (random.nextBoolean() ? 1 : -1)));
            for (int i = 1; i <= ins.n; i++) {
                if (solution.z[i][neighbor] && random.nextDouble() < 0.4) {
                    ctx.removedVisits.add(new Visit(i, neighbor));
                    solution.removeVisit(i, neighbor);
                }
            }
        }
        return ctx;
    }

    private boolean repairGreedy(Instance ins, AlnsSolution solution, DestroyContext ctx, long deadlineNs) {
        return repairByTasks(ins, solution, deadlineNs, false);
    }

    private boolean repairRegret(Instance ins, AlnsSolution solution, DestroyContext ctx, long deadlineNs) {
        return repairByTasks(ins, solution, deadlineNs, true);
    }

    private boolean repairPeriodRebuild(Instance ins, AlnsSolution solution, DestroyContext ctx, long deadlineNs) {
        boolean[] affected = new boolean[ins.l + 1];
        for (int t = 1; t <= ins.l; t++) {
            affected[t] = ctx.destroyedPeriods[t];
        }
        for (Visit v : ctx.removedVisits) {
            affected[v.period] = true;
        }
        for (int t = 1; t <= ins.l; t++) {
            if (!affected[t]) {
                continue;
            }
            if (!selectVisitsForDestroyedPeriod(ins, solution, t, deadlineNs)) {
                break;
            }
            if (!RoutingHeuristics.rebuildPeriodRoutes(ins, solution, t, evaluator)) {
                break;
            }
        }
        return repairByTasks(ins, solution, deadlineNs, false);
    }

    private boolean repairByTasks(Instance ins, AlnsSolution solution, long deadlineNs, boolean regretMode) {
        for (int step = 0; step < MAX_REPAIR_STEPS && System.nanoTime() < deadlineNs; step++) {
            SolutionEvaluator.EvaluationResult structure = evaluator.evaluateStructure(ins, solution);
            if (structure.structureFeasible && structure.firstRawShortagePeriod < 0) {
                return true;
            }

            ArrayList<Task> tasks = collectTasks(ins, solution, structure);
            if (tasks.isEmpty()) {
                return false;
            }

            InsertionCandidate chosen;
            if (regretMode) {
                chosen = chooseByRegret(ins, solution, tasks);
            } else {
                chosen = null;
                for (Task task : tasks) {
                    chosen = bestCandidateForTask(ins, solution, task);
                    if (chosen != null) {
                        break;
                    }
                }
            }
            if (chosen == null) {
                return false;
            }
            solution.insertVisitAt(
                    chosen.customer,
                    chosen.period,
                    chosen.routeIdx,
                    chosen.position,
                    insertionDemandForCurrentState(ins, solution, chosen.customer, chosen.period)
            );
        }
        SolutionEvaluator.EvaluationResult structure = evaluator.evaluateStructure(ins, solution);
        return structure.structureFeasible && structure.firstRawShortagePeriod < 0;
    }

    private InsertionCandidate chooseByRegret(Instance ins, AlnsSolution solution, ArrayList<Task> tasks) {
        InsertionCandidate best = null;
        double bestRegret = -Double.MAX_VALUE;
        for (Task task : tasks) {
            ArrayList<InsertionCandidate> candidates = candidatesForTask(ins, solution, task);
            if (candidates.isEmpty()) {
                continue;
            }
            Collections.sort(candidates, new Comparator<InsertionCandidate>() {
                @Override
                public int compare(InsertionCandidate a, InsertionCandidate b) {
                    return Double.compare(a.delta, b.delta);
                }
            });
            double regret = (candidates.size() >= 2)
                    ? (candidates.get(1).delta - candidates.get(0).delta)
                    : LARGE_REGRET;
            if (regret > bestRegret + SolutionEvaluator.EPS) {
                bestRegret = regret;
                best = candidates.get(0);
            }
        }
        return best;
    }

    private ArrayList<Task> collectTasks(Instance ins, AlnsSolution solution, SolutionEvaluator.EvaluationResult structure) {
        ArrayList<Task> tasks = new ArrayList<Task>();
        for (int i = 1; i <= ins.n; i++) {
            int overflow = firstSupplierOverflowForCustomer(ins, solution, i);
            if (overflow >= 0) {
                tasks.add(new Task(TaskType.SUPPLIER, i, overflow));
            }
        }
        for (int t = 1; t <= ins.l; t++) {
            if (structure.cumulativeRawAvailability[t] + SolutionEvaluator.EPS < structure.requiredRawLowerBound[t]) {
                tasks.add(new Task(TaskType.RAW, -1, t));
            }
        }
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                return Integer.compare(a.deadline, b.deadline);
            }
        });
        return tasks;
    }

    private InsertionCandidate bestCandidateForTask(Instance ins, AlnsSolution solution, Task task) {
        ArrayList<InsertionCandidate> candidates = candidatesForTask(ins, solution, task);
        if (candidates.isEmpty()) {
            return null;
        }
        InsertionCandidate best = candidates.get(0);
        for (InsertionCandidate candidate : candidates) {
            if (candidate.delta < best.delta - SolutionEvaluator.EPS) {
                best = candidate;
            }
        }
        return best;
    }

    private ArrayList<InsertionCandidate> candidatesForTask(Instance ins, AlnsSolution solution, Task task) {
        double[] periodLoads = computePeriodLoads(ins, solution);
        ArrayList<InsertionCandidate> candidates = new ArrayList<InsertionCandidate>();
        if (task.type == TaskType.SUPPLIER) {
            int customer = task.customer;
            for (int t = 1; t <= task.deadline; t++) {
                if (solution.z[customer][t]) {
                    continue;
                }
                int prev = solution.previousVisit(customer, t);
                double demand = ins.g(customer, prev, t);
                if (periodLoads[t] + demand > ins.K * ins.Q + SolutionEvaluator.EPS) {
                    continue;
                }
                RoutingHeuristics.Insertion insertion = RoutingHeuristics.findBestInsertion(ins, solution, customer, t, demand);
                if (insertion == null) {
                    continue;
                }
                IncrementalEvaluator.InsertionDelta id = IncrementalEvaluator.visitInsertionDelta(
                        ins, solution, customer, t, insertion.routeIdx, insertion.position, demand);
                if (id.firstOverflowAfter >= 0 && id.firstOverflowAfter <= task.deadline) {
                    continue;
                }
                candidates.add(new InsertionCandidate(customer, t, insertion.routeIdx, insertion.position, id.totalDelta, demand));
            }
        } else {
            for (int customer = 1; customer <= ins.n; customer++) {
                for (int t = 1; t <= task.deadline; t++) {
                    if (solution.z[customer][t]) {
                        continue;
                    }
                    int prev = solution.previousVisit(customer, t);
                    double demand = ins.g(customer, prev, t);
                    if (periodLoads[t] + demand > ins.K * ins.Q + SolutionEvaluator.EPS) {
                        continue;
                    }
                    RoutingHeuristics.Insertion insertion = RoutingHeuristics.findBestInsertion(ins, solution, customer, t, demand);
                    if (insertion == null) {
                        continue;
                    }
                    double gain = demand;
                    if (gain <= SolutionEvaluator.EPS) {
                        continue;
                    }
                    IncrementalEvaluator.InsertionDelta id = IncrementalEvaluator.visitInsertionDelta(
                            ins, solution, customer, t, insertion.routeIdx, insertion.position, demand);
                    if (id.firstOverflowAfter >= 0 && id.firstOverflowAfter <= task.deadline) {
                        continue;
                    }
                    candidates.add(new InsertionCandidate(customer, t, insertion.routeIdx, insertion.position, id.totalDelta, gain));
                }
            }
        }
        return candidates;
    }

    private int firstSupplierOverflowForCustomer(Instance ins, AlnsSolution solution, int customer) {
        return IncrementalEvaluator.firstOverflowForCustomer(ins, solution.z[customer], customer);
    }

    private boolean selectVisitsForDestroyedPeriod(Instance ins, AlnsSolution solution, int t, long deadlineNs) {
        int additions = 0;
        while (additions < MAX_PERIOD_REBUILD_ADDITIONS && System.nanoTime() < deadlineNs) {
            Integer criticalCustomer = bestCriticalCustomerForPeriod(ins, solution, t);
            if (criticalCustomer != null) {
                solution.z[criticalCustomer.intValue()][t] = true;
                additions++;
                continue;
            }

            SolutionEvaluator.EvaluationResult plan = evaluator.evaluatePlan(ins, solution);
            if (plan.cumulativeRawAvailability[t] + SolutionEvaluator.EPS >= plan.requiredRawLowerBound[t]) {
                return true;
            }

            Integer rawCustomer = bestRawGainCustomerForPeriod(ins, solution, t);
            if (rawCustomer == null) {
                return false;
            }
            solution.z[rawCustomer.intValue()][t] = true;
            additions++;
        }
        return true;
    }

    private Integer bestCriticalCustomerForPeriod(Instance ins, AlnsSolution solution, int period) {
        Integer best = null;
        double bestDemand = -1.0;
        for (int customer = 1; customer <= ins.n; customer++) {
            if (solution.z[customer][period]) {
                continue;
            }
            int prev = solution.previousVisit(customer, period);
            if (ins.mu[customer][prev] != period) {
                continue;
            }
            double demand = ins.g(customer, prev, period);
            if (demand > ins.Q + SolutionEvaluator.EPS) {
                continue;
            }
            if (demand > bestDemand + SolutionEvaluator.EPS) {
                bestDemand = demand;
                best = Integer.valueOf(customer);
            }
        }
        return best;
    }

    private Integer bestRawGainCustomerForPeriod(Instance ins, AlnsSolution solution, int period) {
        double periodLoad = 0.0;
        for (int i = 1; i <= ins.n; i++) {
            if (solution.z[i][period]) {
                double invEnd = ins.Ii0[i];
                for (int tt = 1; tt < period; tt++) {
                    double before = invEnd + ins.s[i][tt];
                    invEnd = solution.z[i][tt] ? 0.0 : before;
                }
                periodLoad += invEnd + ins.s[i][period];
            }
        }
        Integer best = null;
        double bestGain = SolutionEvaluator.EPS;
        for (int customer = 1; customer <= ins.n; customer++) {
            if (solution.z[customer][period]) {
                continue;
            }
            int prev = solution.previousVisit(customer, period);
            double demand = ins.g(customer, prev, period);
            if (demand > ins.Q + SolutionEvaluator.EPS) {
                continue;
            }
            if (periodLoad + demand > ins.K * ins.Q + SolutionEvaluator.EPS) {
                continue;
            }

            solution.z[customer][period] = true;
            int overflow = IncrementalEvaluator.firstOverflowForCustomer(
                    ins, solution.z[customer], customer);
            solution.z[customer][period] = false;
            if (overflow >= 0 && overflow <= period) {
                continue;
            }

            if (demand > bestGain) {
                bestGain = demand;
                best = Integer.valueOf(customer);
            }
        }
        return best;
    }

    private double insertionDemandForCurrentState(Instance ins, AlnsSolution solution, int customer, int period) {
        return ins.g(customer, solution.previousVisit(customer, period), period);
    }

    private double[] computePeriodLoads(Instance ins, AlnsSolution solution) {
        double[] loads = new double[ins.l + 1];
        for (int i = 1; i <= ins.n; i++) {
            for (int t = 1; t <= ins.l; t++) {
                loads[t] += solution.q[i][t];
            }
        }
        return loads;
    }

    private ArrayList<Visit> collectVisits(AlnsSolution solution, Instance ins) {
        ArrayList<Visit> visits = new ArrayList<Visit>();
        for (int i = 1; i <= ins.n; i++) {
            for (int t = 1; t <= ins.l; t++) {
                if (solution.z[i][t]) {
                    visits.add(new Visit(i, t));
                }
            }
        }
        return visits;
    }

    private int chooseDestroyCount(int available, Random random) {
        if (available <= 0) {
            return 0;
        }
        int max = Math.min(available, config.maxDestroyVisits);
        return 1 + random.nextInt(Math.max(1, max));
    }

    private static final class VisitScore {
        final Visit visit;
        final double score;

        VisitScore(Visit visit, double score) {
            this.visit = visit;
            this.score = score;
        }
    }

    private static double[] initWeights(int size) {
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = 1.0;
        }
        return w;
    }

    private void updateWeights(double[] weights, double[] scores, int[] uses) {
        for (int i = 0; i < weights.length; i++) {
            if (uses[i] > 0) {
                double estimate = scores[i] / uses[i];
                weights[i] = (1.0 - config.reactionFactor) * weights[i] + config.reactionFactor * estimate;
                if (weights[i] < 1e-3) {
                    weights[i] = 1e-3;
                }
            }
        }
    }

    private static void resetScores(double[] scores, int[] uses) {
        for (int i = 0; i < scores.length; i++) {
            scores[i] = 0.0;
            uses[i] = 0;
        }
    }

    private int roulette(double[] weights, Random random) {
        double total = 0.0;
        for (double weight : weights) {
            total += weight;
        }
        double draw = random.nextDouble() * total;
        double acc = 0.0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (draw <= acc) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private boolean shouldAccept(double currentObjective, double candidateObjective, double initialTemperature,
                                 long startNs, long deadlineNs, Random random) {
        double delta = candidateObjective - currentObjective;
        if (delta <= SolutionEvaluator.EPS) {
            return true;
        }
        double progress = (double) (System.nanoTime() - startNs) / Math.max(1L, deadlineNs - startNs);
        progress = Math.max(0.0, Math.min(1.0, progress));
        double temperature = initialTemperature * Math.pow(config.finalTemperatureRatio, progress);
        double probability = Math.exp(-delta / Math.max(1e-9, temperature));
        return random.nextDouble() < probability;
    }

    private static void reverse(ArrayList<Integer> values, int left, int right) {
        while (left < right) {
            Integer tmp = values.get(left);
            values.set(left, values.get(right));
            values.set(right, tmp);
            left++;
            right--;
        }
    }

    private static double maxDistance(Instance ins) {
        double max = 1.0;
        for (int i = 0; i < ins.nodeCount; i++) {
            for (int j = 0; j < ins.nodeCount; j++) {
                max = Math.max(max, ins.c[i][j]);
            }
        }
        return max;
    }

    private static void relocateWithinPeriod(AlnsSolution solution, int t, int fromIdx, int fromPos, int toIdx, int insertPos) {
        Route from = solution.routes[t].get(fromIdx);
        Integer customer = from.customers.remove(fromPos);
        if (fromIdx == toIdx && insertPos > fromPos) {
            insertPos--;
        }
        if (from.customers.isEmpty()) {
            solution.routes[t].remove(fromIdx);
            if (toIdx > fromIdx) {
                toIdx--;
            }
        }
        if (toIdx == solution.routes[t].size()) {
            Route route = new Route();
            route.customers.add(customer);
            solution.routes[t].add(route);
        } else {
            Route target = solution.routes[t].get(toIdx);
            int safePos = Math.max(0, Math.min(insertPos, target.customers.size()));
            target.customers.add(safePos, customer);
        }
    }

    private static void copyInto(AlnsSolution target, AlnsSolution source, Instance ins) {
        AlnsSolution copy = source.deepCopy(ins);
        for (int i = 1; i <= ins.n; i++) {
            System.arraycopy(copy.z[i], 0, target.z[i], 0, copy.z[i].length);
            System.arraycopy(copy.q[i], 0, target.q[i], 0, copy.q[i].length);
            System.arraycopy(copy.supplierInventory[i], 0, target.supplierInventory[i], 0, copy.supplierInventory[i].length);
        }
        for (int t = 1; t <= ins.l; t++) {
            target.routes[t].clear();
            for (Route route : copy.routes[t]) {
                target.routes[t].add(route.copy());
            }
        }
        System.arraycopy(copy.y, 0, target.y, 0, copy.y.length);
        System.arraycopy(copy.p, 0, target.p, 0, copy.p.length);
        System.arraycopy(copy.p0, 0, target.p0, 0, copy.p0.length);
        System.arraycopy(copy.i0, 0, target.i0, 0, copy.i0.length);
        target.objective = copy.objective;
        target.routeCost = copy.routeCost;
        target.supplierHoldingCost = copy.supplierHoldingCost;
        target.productionCost = copy.productionCost;
        target.feasible = copy.feasible;
        target.infeasibilityReason = copy.infeasibilityReason;
    }
}
