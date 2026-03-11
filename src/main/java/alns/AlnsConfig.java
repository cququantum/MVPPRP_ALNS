package alns;

public final class AlnsConfig {
    public final long seed;
    public final double timeLimitSec;
    public final int segmentSize;
    public final double reactionFactor;
    public final double sigmaBest;
    public final double sigmaImprove;
    public final double sigmaAccepted;
    public final int moveBudgetPerIteration;
    public final double finalTemperatureRatio;
    public final int maxDestroyVisits;

    public AlnsConfig(
            long seed,
            double timeLimitSec,
            int segmentSize,
            double reactionFactor,
            double sigmaBest,
            double sigmaImprove,
            double sigmaAccepted,
            int moveBudgetPerIteration,
            double finalTemperatureRatio,
            int maxDestroyVisits
    ) {
        this.seed = seed;
        this.timeLimitSec = timeLimitSec;
        this.segmentSize = segmentSize;
        this.reactionFactor = reactionFactor;
        this.sigmaBest = sigmaBest;
        this.sigmaImprove = sigmaImprove;
        this.sigmaAccepted = sigmaAccepted;
        this.moveBudgetPerIteration = moveBudgetPerIteration;
        this.finalTemperatureRatio = finalTemperatureRatio;
        this.maxDestroyVisits = maxDestroyVisits;
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
                60,
                1e-3,
                6
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
                moveBudgetPerIteration,
                finalTemperatureRatio,
                maxDestroyVisits
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
                moveBudgetPerIteration,
                finalTemperatureRatio,
                maxDestroyVisits
        );
    }
}
