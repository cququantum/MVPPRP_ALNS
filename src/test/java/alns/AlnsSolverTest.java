package alns;

import instance.Instance;
import junit.framework.TestCase;
import model.SolveResult;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Random;

public final class AlnsSolverTest extends TestCase {

    public void testSolverIsReproducibleForSameSeed() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsConfig config = AlnsConfig.defaults().withTimeLimitSec(1.0).withSeed(123L);

        SolveResult a = new AlnsSolver(config).solve(ins);
        SolveResult b = new AlnsSolver(config).solve(ins);

        assertTrue(a.feasible);
        assertTrue(b.feasible);
        assertEquals(a.objective, b.objective, 1e-6);
    }

    public void testTwoOptHandlesThreeCustomerRoute() throws Exception {
        Instance ins = AlnsTestSupport.threeCustomerSinglePeriodInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(3, 1, 0, 1);
        solution.insertVisitAt(2, 1, 0, 2);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        evaluator.evaluateStructure(ins, solution);

        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod("applyTwoOpt", Instance.class, AlnsSolution.class);
        method.setAccessible(true);
        boolean improved = ((Boolean) method.invoke(solver, ins, solution)).booleanValue();

        assertTrue(improved);
        assertEquals(Integer.valueOf(3), solution.routes[1].get(0).customers.get(0));
        assertEquals(Integer.valueOf(1), solution.routes[1].get(0).customers.get(1));
        assertEquals(Integer.valueOf(2), solution.routes[1].get(0).customers.get(2));
    }

    public void testRepairPeriodRebuildUsesRemovedVisitPeriods() throws Exception {
        Instance ins = AlnsTestSupport.routeOnlyInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.z[1][1] = true;

        DestroyContext ctx = new DestroyContext(ins.l);
        ctx.removedVisits.add(new Visit(1, 1));

        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod(
                "repairPeriodRebuild",
                Instance.class,
                AlnsSolution.class,
                DestroyContext.class,
                long.class
        );
        method.setAccessible(true);
        boolean repaired = ((Boolean) method.invoke(
                solver,
                ins,
                solution,
                ctx,
                System.nanoTime() + 1_000_000_000L
        )).booleanValue();

        assertTrue(repaired);
        assertEquals(1, solution.routes[1].size());
        assertEquals(Integer.valueOf(1), solution.routes[1].get(0).customers.get(0));
    }

    public void testDestroyProductionPerturbationChangesProductionState() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(2, 2, 0, 0);
        solution.y[1] = 1.0;
        solution.p[1] = 10.0;
        solution.y[2] = 1.0;
        solution.p[2] = 10.0;

        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod(
                "destroyProductionGuided",
                Instance.class,
                AlnsSolution.class,
                Random.class
        );
        method.setAccessible(true);
        method.invoke(solver, ins, solution, new Random(123L));

        assertTrue(solution.y[1] == 0.0 || solution.y[2] == 0.0);
        assertTrue(solution.p[1] == 0.0 || solution.p[2] == 0.0);
    }

    public void testCrossPeriodRelocatePrefilterRestoresOriginalStateWhenRejected() throws Exception {
        Instance ins = AlnsTestSupport.overflowInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        new SolutionEvaluator().evaluateStructure(ins, solution);

        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod(
                "applyCrossPeriodRelocate",
                Instance.class,
                AlnsSolution.class,
                long.class,
                Random.class
        );
        method.setAccessible(true);
        boolean changed = ((Boolean) method.invoke(
                solver,
                ins,
                solution,
                System.nanoTime() + 1_000_000_000L,
                new Random(123L)
        )).booleanValue();

        assertFalse(changed);
        assertTrue(solution.z[1][1]);
        assertFalse(solution.z[1][2]);
        assertEquals(1, solution.routes[1].size());
        assertEquals(Integer.valueOf(1), solution.routes[1].get(0).customers.get(0));
    }

    public void testSupplierCandidatesKeepInsertionThatOnlyOverflowsAfterDeadline() throws Exception {
        Instance ins = AlnsTestSupport.overflowAfterDeadlineInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));

        ArrayList<?> candidates = invokeCandidatesForTask(solver, ins, solution, "SUPPLIER", 1, 2);

        assertTrue(containsCandidateForPeriod(candidates, 1));
    }

    public void testRawCandidatesKeepInsertionThatOnlyOverflowsAfterDeadline() throws Exception {
        Instance ins = AlnsTestSupport.overflowAfterDeadlineInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));

        ArrayList<?> candidates = invokeCandidatesForTask(solver, ins, solution, "RAW", -1, 2);

        assertTrue(containsCandidateForPeriod(candidates, 1));
    }

    public void testSameRouteSwapIsNotRejectedByCrossRouteCapacityCheck() throws Exception {
        Instance ins = AlnsTestSupport.sameRouteSwapCapacityEdgeInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(3, 1, 0, 1);
        solution.insertVisitAt(2, 1, 0, 2);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        evaluator.evaluateStructure(ins, solution);
        double beforeCost = RoutingHeuristics.routeCost(ins, solution.routes[1].get(0));
        double beforeLoad = solution.routes[1].get(0).load;

        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod("applyIntraPeriodSwap", Instance.class, AlnsSolution.class);
        method.setAccessible(true);
        boolean improved = ((Boolean) method.invoke(solver, ins, solution)).booleanValue();

        assertTrue(improved);
        assertEquals(3, solution.routes[1].get(0).customers.size());
        assertEquals(beforeLoad, solution.routes[1].get(0).load, 1e-6);
        assertTrue(RoutingHeuristics.routeCost(ins, solution.routes[1].get(0)) < beforeCost - SolutionEvaluator.EPS);
    }

    public void testDestroyUpperBoundScalesWithInstanceAndRestartFraction() throws Exception {
        Instance small = AlnsTestSupport.smallInstance();
        Instance large = uniformInstance(10, 6);
        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod(
                "destroyUpperBound",
                Instance.class,
                int.class,
                double.class
        );
        method.setAccessible(true);

        int smallUpper = ((Integer) method.invoke(solver, small, Integer.valueOf(4), Double.valueOf(0.25))).intValue();
        int largeUpper = ((Integer) method.invoke(solver, large, Integer.valueOf(60), Double.valueOf(0.25))).intValue();
        int restartUpper = ((Integer) method.invoke(solver, large, Integer.valueOf(60), Double.valueOf(0.35))).intValue();

        assertEquals(4, smallUpper);
        assertEquals(15, largeUpper);
        assertEquals(21, restartUpper);
    }

    public void testDestroyWorstCostVisitsRetainsOverflowCandidatesAndAppliesPenalty() throws Exception {
        Instance ins = AlnsTestSupport.overflowInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        new SolutionEvaluator().evaluateStructure(ins, solution);

        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method destroy = AlnsSolver.class.getDeclaredMethod(
                "destroyWorstCostVisits",
                Instance.class,
                AlnsSolution.class,
                Random.class,
                double.class
        );
        destroy.setAccessible(true);
        DestroyContext ctx = (DestroyContext) destroy.invoke(
                solver,
                ins,
                solution,
                new Random(123L),
                Double.valueOf(1.0)
        );

        Method score = AlnsSolver.class.getDeclaredMethod(
                "scoreWorstDestroyVisit",
                double.class,
                boolean.class,
                double.class
        );
        score.setAccessible(true);
        double safe = ((Double) score.invoke(null, Double.valueOf(-4.0), Boolean.FALSE, Double.valueOf(3.0))).doubleValue();
        double penalized = ((Double) score.invoke(null, Double.valueOf(-4.0), Boolean.TRUE, Double.valueOf(3.0))).doubleValue();

        assertEquals(1, ctx.removedVisits.size());
        assertEquals(new Visit(1, 1), ctx.removedVisits.get(0));
        assertFalse(solution.z[1][1]);
        assertEquals(-4.0, safe, 1e-9);
        assertEquals(-1.0, penalized, 1e-9);
    }

    public void testSolverRestartStatusIncludesPositiveRestartCount() throws Exception {
        Instance ins = AlnsTestSupport.routeOnlyInstance();
        AlnsConfig config = configForTests(0.2, 1, 40, 20, 6, 0.25, 1, 0.35, 3.0, 1.5, 0.6, 1);

        SolveResult result = new AlnsSolver(config).solve(ins);
        int restarts = extractStatusInt(result.status, "restarts=");

        assertTrue(result.status.contains("restarts="));
        assertTrue(restarts > 0);
    }

    public void testLocalSearchRunsCrossPeriodPhaseIndependently() throws Exception {
        Instance ins = crossPeriodImprovementInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        double demand = ins.g(1, 0, 2);
        solution.insertVisitAt(1, 2, 0, 0, demand);

        new SolutionEvaluator().evaluateStructure(ins, solution);
        ProductionReoptimizer.Result production = new ProductionReoptimizer().optimize(ins, solution, 5.0);
        assertTrue(production.feasible);
        solution.setProductionPlan(production);
        new SolutionEvaluator().evaluateFull(ins, solution);

        AlnsConfig config = configForTests(1.0, 100, 0, 1, 6, 0.25, 10, 0.35, 3.0, 1.5, 0.6, 3);
        AlnsSolver solver = new AlnsSolver(config);
        Method method = AlnsSolver.class.getDeclaredMethod(
                "localSearch",
                Instance.class,
                AlnsSolution.class,
                long.class,
                Random.class
        );
        method.setAccessible(true);
        boolean feasible = ((Boolean) method.invoke(
                solver,
                ins,
                solution,
                System.nanoTime() + 1_000_000_000L,
                new Random(123L)
        )).booleanValue();

        assertTrue(feasible);
        assertTrue(solution.z[1][1]);
        assertFalse(solution.z[1][2]);
    }

    public void testQuickProductionScreenMatchesExactFeasibility() throws Exception {
        Instance ins = productionScreenInstance();
        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod("isProductionFeasibleQuick", Instance.class, double[].class);
        method.setAccessible(true);

        double[] feasibleLoads = new double[]{0.0, 5.0, 5.0};
        double[] infeasibleLoads = new double[]{0.0, 0.0, 0.0};

        boolean quickFeasible = ((Boolean) method.invoke(solver, ins, feasibleLoads)).booleanValue();
        boolean quickInfeasible = ((Boolean) method.invoke(solver, ins, infeasibleLoads)).booleanValue();

        AlnsSolution feasible = new AlnsSolution(ins);
        feasible.q[1][1] = 5.0;
        feasible.q[1][2] = 5.0;
        AlnsSolution infeasible = new AlnsSolution(ins);

        boolean exactFeasible = new ProductionReoptimizer().optimize(ins, feasible, 5.0).feasible;
        boolean exactInfeasible = new ProductionReoptimizer().optimize(ins, infeasible, 5.0).feasible;

        assertEquals(exactFeasible, quickFeasible);
        assertEquals(exactInfeasible, quickInfeasible);
        assertTrue(quickFeasible);
        assertFalse(quickInfeasible);
    }

    public void testTemporalRelatednessCanFlipOrderingAgainstLegacyWeights() throws Exception {
        Instance ins = temporalOrderingInstance();
        AlnsSolver solver = new AlnsSolver(AlnsConfig.defaults().withTimeLimitSec(1.0));
        Method method = AlnsSolver.class.getDeclaredMethod(
                "relatednessScore",
                Instance.class,
                Visit.class,
                Visit.class
        );
        method.setAccessible(true);
        Method maxDistanceMethod = AlnsSolver.class.getDeclaredMethod("maxDistance", Instance.class);
        maxDistanceMethod.setAccessible(true);

        Visit seed = new Visit(1, 1);
        Visit spatiallyCloseTemporallyFar = new Visit(2, 3);
        Visit spatiallyFarTemporallyClose = new Visit(3, 1);

        double newScoreA = ((Double) method.invoke(solver, ins, seed, spatiallyCloseTemporallyFar)).doubleValue();
        double newScoreB = ((Double) method.invoke(solver, ins, seed, spatiallyFarTemporallyClose)).doubleValue();

        double maxDistance = ((Double) maxDistanceMethod.invoke(null, ins)).doubleValue();
        double oldDistA = ins.c[seed.customer][spatiallyCloseTemporallyFar.customer] / maxDistance;
        double oldDistB = ins.c[seed.customer][spatiallyFarTemporallyClose.customer] / maxDistance;
        double oldTimeA = Math.abs(seed.period - spatiallyCloseTemporallyFar.period) / (double) (ins.l - 1);
        double oldTimeB = Math.abs(seed.period - spatiallyFarTemporallyClose.period) / (double) (ins.l - 1);
        double oldScoreA = 0.7 * oldDistA + 0.3 * oldTimeA;
        double oldScoreB = 0.7 * oldDistB + 0.3 * oldTimeB;

        assertTrue(oldScoreA < oldScoreB);
        assertTrue(newScoreA > newScoreB);
    }

    public void testRestartRequiresBestAndCurrentStagnation() throws Exception {
        Method method = AlnsSolver.class.getDeclaredMethod(
                "shouldRestart",
                int.class,
                int.class,
                int.class,
                int.class
        );
        method.setAccessible(true);

        boolean blocked = ((Boolean) method.invoke(null,
                Integer.valueOf(1000),
                Integer.valueOf(1000),
                Integer.valueOf(299),
                Integer.valueOf(300))).booleanValue();
        boolean triggered = ((Boolean) method.invoke(null,
                Integer.valueOf(1000),
                Integer.valueOf(1000),
                Integer.valueOf(300),
                Integer.valueOf(300))).booleanValue();

        assertFalse(blocked);
        assertTrue(triggered);
    }

    private ArrayList<?> invokeCandidatesForTask(AlnsSolver solver, Instance ins, AlnsSolution solution,
                                                 String taskTypeName, int customer, int deadline) throws Exception {
        Class<?> taskTypeClass = Class.forName("alns.AlnsSolver$TaskType");
        @SuppressWarnings("unchecked")
        Object taskType = Enum.valueOf((Class<Enum>) taskTypeClass.asSubclass(Enum.class), taskTypeName);

        Class<?> taskClass = Class.forName("alns.AlnsSolver$Task");
        Constructor<?> constructor = taskClass.getDeclaredConstructor(taskTypeClass, int.class, int.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance(taskType, Integer.valueOf(customer), Integer.valueOf(deadline));

        Method method = AlnsSolver.class.getDeclaredMethod("candidatesForTask", Instance.class, AlnsSolution.class, taskClass);
        method.setAccessible(true);
        return (ArrayList<?>) method.invoke(solver, ins, solution, task);
    }

    private boolean containsCandidateForPeriod(ArrayList<?> candidates, int period) throws Exception {
        for (Object candidate : candidates) {
            Field field = candidate.getClass().getDeclaredField("period");
            field.setAccessible(true);
            if (field.getInt(candidate) == period) {
                return true;
            }
        }
        return false;
    }

    private AlnsConfig configForTests(double timeLimitSec,
                                      int segmentSize,
                                      int intraBudget,
                                      int crossBudget,
                                      int minDestroyVisits,
                                      double destroyFraction,
                                      int restartSegments,
                                      double restartDestroyFraction,
                                      double restartTemperatureMultiplier,
                                      double overflowPenaltyFactor,
                                      double relatedTemporalWeight,
                                      int currentStagnationSegments) {
        return new AlnsConfig(
                123L,
                timeLimitSec,
                segmentSize,
                0.2,
                10.0,
                5.0,
                1.0,
                1e-3,
                intraBudget,
                crossBudget,
                minDestroyVisits,
                destroyFraction,
                restartSegments,
                restartDestroyFraction,
                restartTemperatureMultiplier,
                overflowPenaltyFactor,
                relatedTemporalWeight,
                currentStagnationSegments
        );
    }

    private int extractStatusInt(String status, String key) {
        int start = status.indexOf(key);
        assertTrue(start >= 0);
        start += key.length();
        int end = status.indexOf(')', start);
        if (end < 0) {
            end = status.length();
        }
        return Integer.parseInt(status.substring(start, end));
    }

    private Instance uniformInstance(int customerCount, int periods) throws Exception {
        StringBuilder txt = new StringBuilder();
        txt.append("InstanceType:\t1\n");
        txt.append("CustomerNumber:\t").append(customerCount).append('\n');
        txt.append("PeriodNumber:\t").append(periods).append('\n');
        txt.append("VechileNumber:\t").append(customerCount).append('\n');
        txt.append("VehicleCapacity:\t40\n");
        txt.append("Supplier COORDX COORDY InitLevel MaxLevel ProdCapacity HoldCost VarCost FixCost\n");
        txt.append("0 0 0 0 80 40 1 1 1\n");
        txt.append("Retailer COORDX COORDY InitLevel MaxLevel Demand HoldCost\n");
        for (int customer = 1; customer <= customerCount; customer++) {
            txt.append(customer)
                    .append(' ')
                    .append(customer)
                    .append(" 0 10 30 2 1\n");
        }
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = true;
        return Instance.fromString(txt.toString(), options);
    }

    private Instance crossPeriodImprovementInstance() throws Exception {
        String txt =
                "InstanceType:\t1\n" +
                "CustomerNumber:\t1\n" +
                "PeriodNumber:\t2\n" +
                "VechileNumber:\t1\n" +
                "VehicleCapacity:\t30\n" +
                "Supplier COORDX COORDY InitLevel MaxLevel ProdCapacity HoldCost VarCost FixCost\n" +
                "0 0 0 0 20 20 1 0 0\n" +
                "Retailer COORDX COORDY InitLevel MaxLevel Demand HoldCost\n" +
                "1 3 0 10 20 5 1\n";
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = false;
        return Instance.fromString(txt, options);
    }

    private Instance productionScreenInstance() throws Exception {
        String txt =
                "InstanceType:\t1\n" +
                "CustomerNumber:\t1\n" +
                "PeriodNumber:\t2\n" +
                "VechileNumber:\t1\n" +
                "VehicleCapacity:\t30\n" +
                "Supplier COORDX COORDY InitLevel MaxLevel ProdCapacity HoldCost VarCost FixCost\n" +
                "0 0 0 0 20 10 1 0 0\n" +
                "Retailer COORDX COORDY InitLevel MaxLevel Demand HoldCost\n" +
                "1 3 0 10 20 5 1\n";
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = false;
        options.P00 = 0.0;
        options.Lp = 20.0;
        Instance ins = Instance.fromString(txt, options);
        ins.dt[1] = 5.0;
        ins.dt[2] = 5.0;
        return ins;
    }

    private Instance temporalOrderingInstance() throws Exception {
        String txt =
                "InstanceType:\t1\n" +
                "CustomerNumber:\t4\n" +
                "PeriodNumber:\t3\n" +
                "VechileNumber:\t2\n" +
                "VehicleCapacity:\t30\n" +
                "Supplier COORDX COORDY InitLevel MaxLevel ProdCapacity HoldCost VarCost FixCost\n" +
                "0 0 0 0 50 20 1 1 1\n" +
                "Retailer COORDX COORDY InitLevel MaxLevel Demand HoldCost\n" +
                "1 0 0 5 20 2 1\n" +
                "2 0 0 5 20 2 1\n" +
                "3 2 0 5 20 2 1\n" +
                "4 4 0 5 20 2 1\n";
        Instance.Options options = Instance.Options.defaults();
        options.distanceMode = Instance.Options.DistanceMode.EUCLIDEAN_FLOAT;
        options.autoSetDt = true;
        return Instance.fromString(txt, options);
    }
}
