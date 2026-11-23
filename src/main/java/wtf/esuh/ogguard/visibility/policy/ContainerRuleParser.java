package wtf.esuh.ogguard.visibility.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds a {@link ContainerRule} from the plain map of the {@code container-concealment} config section. */
public final class ContainerRuleParser {

    private ContainerRuleParser() {
    }

    public static ContainerRule parse(Map<String, ?> s) {
        ContainerRule d = ContainerRule.defaults();
        Object enabled = s.get("enabled");
        if (enabled != null && !(enabled instanceof Boolean)) {
            throw new IllegalArgumentException("container-concealment.enabled must be true or false");
        }
        Object distance = s.get("max-distance");
        if (distance != null && !(distance instanceof Number)) {
            throw new IllegalArgumentException("container-concealment.max-distance must be a number");
        }
        Object interval = s.get("check-interval-ticks");
        if (interval != null && !(interval instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()))) {
            throw new IllegalArgumentException("container-concealment.check-interval-ticks must be a whole number");
        }
        Object replace = s.get("replace-block");
        if (replace != null && !(replace instanceof Boolean)) {
            throw new IllegalArgumentException("container-concealment.replace-block must be true or false");
        }
        Object replacementBlock = s.get("replacement-block");
        Replacement replacement = d.replacementBlock();
        if (replacementBlock != null) {
            try {
                replacement = Replacement.valueOf(String.valueOf(replacementBlock).trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("container-concealment.replacement-block must be AIR, STONE or DEEPSLATE");
            }
        }
        Object types = s.get("block-entity-types");
        List<String> typeList;
        if (types == null) {
            typeList = new ArrayList<>(d.blockEntityTypes());
        } else if (types instanceof List<?> list) {
            typeList = new ArrayList<>();
            for (Object o : list) {
                typeList.add(String.valueOf(o));
            }
        } else {
            throw new IllegalArgumentException("container-concealment.block-entity-types must be a list");
        }
        return new ContainerRule(
                enabled == null ? d.enabled() : (Boolean) enabled,
                distance == null ? d.maxDistance() : ((Number) distance).doubleValue(),
                interval == null ? d.checkIntervalTicks() : ((Number) interval).intValue(),
                java.util.Set.copyOf(typeList),
                replace == null ? d.replaceBlock() : (Boolean) replace,
                replacement);
    }
}
