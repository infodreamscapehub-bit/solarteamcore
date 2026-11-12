package ro.solarstudios.flowcity.vehicle;

/**
 * Catalogul vehiculelor FlowCity: caroserie (forma), livrea (culori + inscriptie), performante, locuri, girofar/sirena.
 * Toate modelele si inscriptiile sunt desenate de noi; numele nu folosesc marci reale.
 */
public final class VehicleTypes {
    private VehicleTypes() {}

    public static final int SEDAN = 0, SUV = 1, VAN = 2, HATCH = 3, SPORT = 4;
    public static final int BODIES = 5;

    /** Livrea: -1 = civil (culoarea aleasa). Decal = randul din car_decals.png (-1 = fara). */
    public static final class Livery {
        public final int body;      // culoare caroserie (-1 = culoarea masinii)
        public final int stripe;    // banda laterala (-1 = fara)
        public final int decal;
        public final int beaconA;   // culorile girofarului (0 = fara girofar)
        public final int beaconB;
        public final boolean siren; // are si sunet de sirena
        public final boolean sign;  // caseta taxi pe plafon

        Livery(int body, int stripe, int decal, int beaconA, int beaconB, boolean siren, boolean sign) {
            this.body = body;
            this.stripe = stripe;
            this.decal = decal;
            this.beaconA = beaconA;
            this.beaconB = beaconB;
            this.siren = siren;
            this.sign = sign;
        }
    }

    static final Livery CIVIL = new Livery(-1, -1, -1, 0, 0, false, false);
    static final Livery POLICE = new Livery(0xF2F2F0, 0x1F4E9C, 0, 0xFF3B30, 0x3B7BFF, true, false);
    static final Livery JANDARMERIE = new Livery(0x1E2B4A, 0xE9E9E6, 1, 0x3B7BFF, 0xE8F0FF, true, false);
    static final Livery AMBULANCE = new Livery(0xF4F4F2, 0xD0302A, 2, 0xFF3B30, 0xF2F2F2, true, false);
    static final Livery PRIMARIE = new Livery(0xF2F2F0, 0x2E8B57, 3, 0xF2A03B, 0xF2A03B, false, false);
    static final Livery MECANIC = new Livery(0xE9A23B, 0x22252A, 4, 0xF2A03B, 0xF2A03B, false, false);
    static final Livery TAXI = new Livery(0xF2C230, 0x22252A, 5, 0, 0, false, true);

    public static final class Type {
        public final String name;
        public final int body;
        public final Livery livery;
        public final double speed;   // multiplicator viteza maxima
        public final double accel;   // multiplicator acceleratie
        public final int seats;
        public final int defaultColor;

        Type(String name, int body, Livery livery, double speed, double accel, int seats, int defaultColor) {
            this.name = name;
            this.body = body;
            this.livery = livery;
            this.speed = speed;
            this.accel = accel;
            this.seats = seats;
            this.defaultColor = defaultColor;
        }
    }

    /** Indexul e salvat in masina (Variant) - nu schimba ordinea, doar adauga la final. */
    public static final Type[] TYPES = {
        new Type("Sedan", SEDAN, CIVIL, 1.0, 1.0, 4, 0xE8EEF9),
        new Type("Politie", SEDAN, POLICE, 1.12, 1.15, 4, 0xF2F2F0),
        new Type("Taxi", SEDAN, TAXI, 0.95, 1.0, 4, 0xF2C230),
        new Type("SUV", SUV, CIVIL, 0.92, 0.95, 4, 0x2B2F36),
        new Type("Jandarmerie", SUV, JANDARMERIE, 1.0, 1.05, 4, 0x1E2B4A),
        new Type("Duba", VAN, CIVIL, 0.8, 0.8, 4, 0xF2F2F0),
        new Type("Duba Politie", VAN, POLICE, 0.9, 0.9, 4, 0xF2F2F0),
        new Type("Ambulanta", VAN, AMBULANCE, 0.95, 0.95, 4, 0xF4F4F2),
        new Type("Duba Primarie", VAN, PRIMARIE, 0.8, 0.8, 4, 0xF2F2F0),
        new Type("Service Auto", VAN, MECANIC, 0.85, 0.85, 4, 0xE9A23B),
        new Type("Compacta", HATCH, CIVIL, 0.85, 1.05, 4, 0xC8283C),
        new Type("Sport", SPORT, CIVIL, 1.35, 1.45, 2, 0x2F6FDB),
    };

    public static Type get(int i) {
        return TYPES[Math.max(0, Math.min(TYPES.length - 1, i))];
    }

    /** Dimensiunile caroseriilor (pixeli de model, sol = 24, fata = -Z). */
    public static final class Spec {
        public final float w, l, sill, belt, roof, cabF, cabB, wheelZ, wheelR, cargoFrom;
        public final double seatY, seatFront, seatRear;

        Spec(float w, float l, float sill, float belt, float roof, float cabF, float cabB, float wheelZ, float wheelR, float cargoFrom,
             double seatY, double seatFront, double seatRear) {
            this.w = w;
            this.l = l;
            this.sill = sill;
            this.belt = belt;
            this.roof = roof;
            this.cabF = cabF;
            this.cabB = cabB;
            this.wheelZ = wheelZ;
            this.wheelR = wheelR;
            this.cargoFrom = cargoFrom;
            this.seatY = seatY;
            this.seatFront = seatFront;
            this.seatRear = seatRear;
        }
    }

    public static final Spec[] SPECS = {
        new Spec(14, 32, 19, 12, 4.5f, -11, 14, 20, 4.5f, 99, 0.28, 0.35, -0.75),     // sedan
        new Spec(15, 33, 17, 9, 0, -13, 27, 21, 5.5f, 99, 0.45, 0.40, -0.75),         // SUV
        new Spec(15, 37, 17, 9, -7, -27, 37, 24, 5f, -11, 0.48, 1.05, -0.35),        // duba (cabina fata + cala)
        new Spec(13, 26, 19, 12, 4.5f, -8, 23, 17, 4.5f, 99, 0.28, 0.15, -0.70),      // compacta
        new Spec(15, 33, 20, 15, 10, -5, 11, 21, 4.5f, 99, 0.12, 0.05, 0),            // sport
    };
}
