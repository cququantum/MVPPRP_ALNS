package alns;

import instance.Instance;
import junit.framework.TestCase;

public final class SolutionEvaluatorTest extends TestCase {

    public void testEvaluateStructureRecomputesInventoryAndPickup() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsSolution solution = new AlnsSolution(ins);
        solution.insertVisitAt(1, 1, 0, 0);
        solution.insertVisitAt(2, 2, 0, 0);

        SolutionEvaluator evaluator = new SolutionEvaluator();
        SolutionEvaluator.EvaluationResult result = evaluator.evaluateStructure(ins, solution);

        assertTrue(result.structureFeasible);
        assertEquals(15.0, solution.q[1][1], 1e-6);
        assertEquals(0.0, solution.supplierInventory[1][1], 1e-6);
        assertEquals(5.0, solution.supplierInventory[1][2], 1e-6);
        assertEquals(20.0, solution.q[2][2], 1e-6);
        assertTrue(result.routeCost > 0.0);
    }

    public void testInsertAndRemoveKeepRouteLoadConsistent() throws Exception {
        Instance ins = AlnsTestSupport.smallInstance();
        AlnsSolution solution = new AlnsSolution(ins);

        solution.insertVisitAt(1, 1, 0, 0, 15.0);
        solution.insertVisitAt(2, 1, 0, 1, 10.0);
        assertEquals(25.0, solution.routes[1].get(0).load, 1e-6);

        solution.removeVisit(1, 1);
        assertEquals(10.0, solution.routes[1].get(0).load, 1e-6);
    }
}
