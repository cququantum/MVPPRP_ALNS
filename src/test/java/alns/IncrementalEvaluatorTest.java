package alns;

import instance.Instance;
import junit.framework.TestCase;

public final class IncrementalEvaluatorTest extends TestCase {

    public void testSupplierHoldingCostAndOverflowForSingleCustomer() throws Exception {
        Instance ins = AlnsTestSupport.overflowInstance();
        AlnsSolution solution = new AlnsSolution(ins);

        assertEquals(20.0, IncrementalEvaluator.supplierHoldingCostForCustomer(ins, solution.z[1], 1), 1e-6);
        assertEquals(2, IncrementalEvaluator.firstOverflowForCustomer(ins, solution.z[1], 1));

        solution.z[1][1] = true;
        assertEquals(6.0, IncrementalEvaluator.supplierHoldingCostForCustomer(ins, solution.z[1], 1), 1e-6);
        assertEquals(-1, IncrementalEvaluator.firstOverflowForCustomer(ins, solution.z[1], 1));
    }

    public void testVisitRemovalDeltaMatchesFullRecompute() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(2, 2, 0, 0);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        SolutionEvaluator.EvaluationResult base = evaluator.evaluateStructure(ins, solution);

        IncrementalEvaluator.RemovalDelta delta = IncrementalEvaluator.visitRemovalDelta(ins, solution, 1, 1);
        assertFalse(delta.causesOverflow);

        AlnsSolution candidate = solution.deepCopy(ins);
        candidate.removeVisit(1, 1);
        SolutionEvaluator.EvaluationResult eval = evaluator.evaluateStructure(ins, candidate);
        double expected = (eval.routeCost + eval.supplierHoldingCost) - (base.routeCost + base.supplierHoldingCost);
        assertEquals(expected, delta.totalDelta, 1e-6);
    }

    public void testVisitInsertionDeltaMatchesFullRecompute() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(2, 2, 0, 0);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        SolutionEvaluator.EvaluationResult base = evaluator.evaluateStructure(ins, solution);

        double demand = ins.g(1, solution.previousVisit(1, 1), 1);
        IncrementalEvaluator.InsertionDelta delta = IncrementalEvaluator.visitInsertionDelta(ins, solution, 1, 1, 0, 0, demand);
        assertFalse(delta.causesOverflow);

        AlnsSolution candidate = solution.deepCopy(ins);
        candidate.insertVisitAt(1, 1, 0, 0, demand);
        SolutionEvaluator.EvaluationResult eval = evaluator.evaluateStructure(ins, candidate);
        double expected = (eval.routeCost + eval.supplierHoldingCost) - (base.routeCost + base.supplierHoldingCost);
        assertEquals(expected, delta.totalDelta, 1e-6);
    }

    public void testVisitInsertionDeltaReportsFirstOverflowAfterInsertedPeriod() throws Exception {
        Instance ins = AlnsTestSupport.overflowAfterDeadlineInstance();
        AlnsSolution solution = new AlnsSolution(ins);

        double demand = ins.g(1, solution.previousVisit(1, 1), 1);
        IncrementalEvaluator.InsertionDelta delta = IncrementalEvaluator.visitInsertionDelta(
                ins, solution, 1, 1, 0, 0, demand);

        assertTrue(delta.causesOverflow);
        assertEquals(3, delta.firstOverflowAfter);
    }

    public void testRoutingOnlyRelocateDeltaMatchesActualRouteCostChange() throws Exception {
        Instance ins = AlnsTestSupport.threeCustomerSinglePeriodInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(2, 1, 0, 1);
        solution.insertVisitAt(3, 1, 1, 0);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        evaluator.evaluateStructure(ins, solution);

        double before = totalRouteCost(ins, solution, 1);
        double delta = IncrementalEvaluator.routingOnlyRelocateDelta(ins, solution, 1, 0, 1, 1, 1);

        AlnsSolution candidate = solution.deepCopy(ins);
        Route from = candidate.routes[1].get(0);
        Integer customer = from.customers.remove(1);
        candidate.routes[1].get(1).customers.add(1, customer);
        double after = totalRouteCost(ins, candidate, 1);
        assertEquals(after - before, delta, 1e-6);
    }

    public void testRoutingOnlySwapDeltaMatchesActualRouteCostChange() throws Exception {
        Instance ins = AlnsTestSupport.threeCustomerSinglePeriodInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(2, 1, 0, 1);
        solution.insertVisitAt(3, 1, 1, 0);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        evaluator.evaluateStructure(ins, solution);

        double before = totalRouteCost(ins, solution, 1);
        double delta = IncrementalEvaluator.routingOnlySwapDelta(ins, solution, 1, 0, 1, 1, 0);

        AlnsSolution candidate = solution.deepCopy(ins);
        candidate.routes[1].get(0).customers.set(1, Integer.valueOf(3));
        candidate.routes[1].get(1).customers.set(0, Integer.valueOf(2));
        double after = totalRouteCost(ins, candidate, 1);
        assertEquals(after - before, delta, 1e-6);
    }

    private double totalRouteCost(Instance ins, AlnsSolution solution, int period) {
        double cost = 0.0;
        for (Route route : solution.routes[period]) {
            cost += RoutingHeuristics.routeCost(ins, route);
        }
        return cost;
    }
}
