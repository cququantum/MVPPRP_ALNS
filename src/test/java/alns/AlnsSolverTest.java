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
}
