package ro.solarstudios.flowcity.bridge.phone;

/** Validarea pura a unui transfer bancar (sume rotunjite la 2 zecimale, fara bani negativi). */
public final class BankLogic {
    private BankLogic() {}

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final double amount;
        public final double fee;
        public final double total;

        Result(boolean ok, String error, double amount, double fee) {
            this.ok = ok;
            this.error = error;
            this.amount = amount;
            this.fee = fee;
            this.total = round(amount + fee);
        }
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static Result validate(double amountIn, double balance, double min, double max, double feePercent, boolean toSelf) {
        if (Double.isNaN(amountIn) || Double.isInfinite(amountIn)) return new Result(false, "Suma invalida.", 0, 0);
        double amount = round(amountIn);
        if (toSelf) return new Result(false, "Nu iti poti transfera singur bani.", amount, 0);
        if (amount <= 0) return new Result(false, "Suma invalida.", amount, 0);
        if (amount < min) return new Result(false, "Suma minima este " + min + ".", amount, 0);
        if (amount > max) return new Result(false, "Suma maxima per transfer este " + max + ".", amount, 0);
        double fee = feePercent > 0 ? round(amount * feePercent / 100.0) : 0;
        if (round(amount + fee) > round(balance) + 1e-9) return new Result(false, "Fonduri insuficiente.", amount, fee);
        return new Result(true, null, amount, fee);
    }
}
