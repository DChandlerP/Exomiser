package de.charite.compbio.exomiser.filter;

import de.charite.compbio.exomiser.exome.VariantEvaluation;
import jannovar.common.VariantType;
import jannovar.exome.Variant;
import jannovar.exome.VariantTypeCounter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter variants according to whether they are on target (i.e., located within
 * an exon or splice junction) or not. This filter also has the side effect of
 * calculating the counts of the various variant classes. The class uses the
 * annotations made by classes from the {@code jannovar.annotation} package etc.
 * <P>
 * Note that this class does not require a corresponding
 * {@link exomizer.filter.Triage Triage} object, because variants that do not
 * pass the filter are simply removed.
 *
 * @author Peter N Robinson
 * @version 0.16 (20 December, 2013)
 */
public class TargetFilter implements Filter {
    
    private final Logger logger = LoggerFactory.getLogger(TargetFilter.class);

    private final FilterType filterType = FilterType.TARGET_FILTER;

    /**
     * Number of variants analyzed by filter
     */
    private int n_before;
    /**
     * Number of variants passing filter
     */
    private int n_after;

    private VariantTypeCounter vtypeCounter = null;

    private List<String> messages = null;

    /**
     * A set of off-target variant types such as Intergenic that we will filter
     * out from further consideration.
     */
    private EnumSet<VariantType> offTarget = null;

    public VariantTypeCounter getVariantTypeCounter() {
        return this.vtypeCounter;
    }

    /**
     * The constructor initializes the set of off-target
     * {@link jannovar.common.VariantType VariantType} constants, e.g.,
     * INTERGENIC, that we will filter out using this class.
     */
    public TargetFilter() {

        this.offTarget = EnumSet.of(VariantType.DOWNSTREAM, VariantType.INTERGENIC, VariantType.INTRONIC,
                VariantType.ncRNA_INTRONIC, VariantType.SYNONYMOUS,
                VariantType.UPSTREAM, VariantType.ERROR);
        this.messages = new ArrayList<String>();
    }

    /**
     * Take a list of variants and apply the filter to each variant. If a
     * variant does not pass the filter, remove it.
     */
    @Override
    public void filterVariants(List<VariantEvaluation> variantEvaluationtList) {
        if (variantEvaluationtList.isEmpty()) {
            logger.error("Size of variant list is zero - no variants to filter.");
            return;
        }
        int M = variantEvaluationtList.get(0).getNumberOfIndividuals();
        this.vtypeCounter = new VariantTypeCounter(M);

        Iterator<VariantEvaluation> it = variantEvaluationtList.iterator();
        this.n_before = variantEvaluationtList.size();
        while (it.hasNext()) {
            VariantEvaluation ve = it.next();
            Variant v = ve.getVariant();
            VariantType vtype = v.getVariantTypeConstant();
            this.vtypeCounter.incrementCount(v);
            if (this.offTarget.contains(vtype)) {
                it.remove();
            }
        }
        this.n_after = variantEvaluationtList.size();
        int removed = n_before - n_after;
        String s = String.format("Removed a total of %d off-target variants from further consideration", removed);
        this.messages.add(s);
        s = "Off target variants are defined as intergenic or intronic but not in splice sequences";
        this.messages.add(s);

    }

    /**
     * get a list of messages that represent the process and result of applying
     * the filter. This list can be used to make an HTML list for explaining the
     * result to users (for instance).
     */
    @Override
    public List<String> getMessages() {

        return this.messages;
    }

    @Override
    public String getFilterName() {
        return "Exome target region";
    }

    /**
     * @return an integer constant (as defined in exomizer.common.Constants)
     * that will act as a flag to generate the output HTML dynamically depending
     * on the filters that the user has chosen.
     */
    @Override
    public FilterType getFilterType() {
        return filterType;
    }

    /**
     * Get number of variants before filter was applied
     */
    @Override
    public int getBefore() {
        return this.n_before;
    }

    /**
     * Get number of variants after filter was applied
     */
    @Override
    public int getAfter() {
        return this.n_after;
    }

    /**
     * Should this Filter be shown in the HTML output?
     */
    public boolean displayInHTML() {
        return true;
    }

    @Override
    public String toString() {
        return "TargetFilter{" + "filterType=" + filterType + ", offTarget=" + offTarget + '}';
    }

}