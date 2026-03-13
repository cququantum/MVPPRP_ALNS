package alns;

public final class AlnsConfig {
    public final long seed;
    public final double timeLimitSec;
    public final int segmentSize;
    public final double reactionFactor;
    public final double sigmaBest;
    public final double sigmaImprove;
    public final double sigmaAccepted;
    public final double finalTemperatureRatio;
    public final int intraPeriodMoveBudgetPerIteration;
    public final int crossPeriodMoveBudgetPerIteration;
    public final int minDestroyVisits;
    public final double destroyFraction;
    public final int restartStagnationSegments;
    public final double restartDestroyFraction;
    public final double restartTemperatureMultiplier;
    public final double worstDestroyOverflowPenaltyFactor;
    public final double relatedTemporalWeight;

    public AlnsConfig(
            long seed,
            double timeLimitSec,
            int segmentSize,
            double reactionFactor,
            double sigmaBest,
            double sigmaImprove,
            double sigmaAccepted,
            double finalTemperatureRatio,
            int intraPeriodMoveBudgetPerIteration,
            int crossPeriodMoveBudgetPerIteration,
            int minDestroyVisits,
            double destroyFraction,
            int restartStagnationSegments,
            double restartDestroyFraction,
            double restartTemperatureMultiplier,
            double worstDestroyOverflowPenaltyFactor,
            double relatedTemporalWeight
    ) {
        this.seed = seed;
        this.timeLimitSec = timeLimitSec;
        this.segmentSize = segmentSize;
        this.reactionFactor = reactionFactor;
        this.sigmaBest = sigmaBest;
        this.sigmaImprove = sigmaImprove;
        this.sigmaAccepted = sigmaAccepted;
        this.finalTemperatureRatio = finalTemperatureRatio;
        this.intraPeriodMoveBudgetPerIteration = intraPeriodMoveBudgetPerIteration;
        this.crossPeriodMoveBudgetPerIteration = crossPeriodMoveBudgetPerIteration;
        this.minDestroyVisits = minDestroyVisits;
        this.destroyFraction = destroyFraction;
        this.restartStagnationSegments = restartStagnationSegments;
        this.restartDestroyFraction = restartDestroyFraction;
        this.restartTemperatureMultiplier = restartTemperatureMultiplier;
        this.worstDestroyOverflowPenaltyFactor = worstDestroyOverflowPenaltyFactor;
        this.relatedTemporalWeight = relatedTemporalWeight;
    }

    public static AlnsConfig defaults() {
        return new AlnsConfig(
                20260310L,
                60.0,
                100,
                0.2,
                10.0,
                5.0,
                1.0,
                1e-3,
                40,
                20,
                6,
                0.25,
                10,
                0.35,
                3.0,
                1.5,
                0.6
        );
    }

    public AlnsConfig withSeed(long x) {
        return new AlnsConfig(
                x,
                timeLimitSec,
                segmentSize,
                reactionFactor,
                sigmaBest,
                sigmaImprove,
                sigmaAccepted,
                finalTemperatureRatio,
                intraPeriodMoveBudgetPerIteration,
                crossPeriodMoveBudgetPerIteration,
                minDestroyVisits,
                destroyFraction,
                restartStagnationSegments,
                restartDestroyFraction,
                restartTemperatureMultiplier,
                worstDestroyOverflowPenaltyFactor,
                relatedTemporalWeight
        );
    }

    public AlnsConfig withTimeLimitSec(double x) {
        return new AlnsConfig(
                seed,
                x,
                segmentSize,
                reactionFactor,
                sigmaBest,
                sigmaImprove,
                sigmaAccepted,
                finalTemperatureRatio,
                intraPeriodMoveBudgetPerIteration,
                crossPeriodMoveBudgetPerIteration,
                minDestroyVisits,
                destroyFraction,
                restartStagnationSegments,
                restartDestroyFraction,
                restartTemperatureMultiplier,
                worstDestroyOverflowPenaltyFactor,
                relatedTemporalWeight
        );
    }
}
