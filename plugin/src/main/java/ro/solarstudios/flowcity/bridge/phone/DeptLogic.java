package ro.solarstudios.flowcity.bridge.phone;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Round-robin pentru departamente (pur): alege urmatorul membru disponibil dupa ultimul ales. */
public final class DeptLogic {
    private DeptLogic() {}

    /**
     * @param duty      membrii in tura, in ordinea intrarii in tura
     * @param last      ultimul ales (poate lipsi din lista sau fi null -> se incepe de la primul)
     * @param available filtru (online, liber, neincercat)
     * @return urmatorul disponibil in ordine circulara sau null
     */
    public static UUID pick(List<UUID> duty, UUID last, Predicate<UUID> available) {
        int n = duty.size();
        if (n == 0) return null;
        int start = last == null ? 0 : duty.indexOf(last) + 1; // lipsa -> -1+1 = 0
        for (int i = 0; i < n; i++) {
            UUID u = duty.get((start + i) % n);
            if (available.test(u)) return u;
        }
        return null;
    }
}
