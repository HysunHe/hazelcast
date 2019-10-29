/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.calcite.physical.distribution;

import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.hazelcast.sql.impl.calcite.physical.distribution.DistributionType.ANY;
import static com.hazelcast.sql.impl.calcite.physical.distribution.DistributionType.DISTRIBUTED;
import static com.hazelcast.sql.impl.calcite.physical.distribution.DistributionType.REPLICATED;
import static com.hazelcast.sql.impl.calcite.physical.distribution.DistributionType.SINGLETON;

/**
 * Distribution trait. Defines how the given relation is distributed in the cluster. We define three principal
 * distribution types:
 * <ul>
 *     <li>{@code DISTRIBUTED} - the relation is distributed between members, and every member contains a subset of
 *     tuples. The relation may have zero, one or several distribution column groups. Every group may have one or
 *     several columns. Most often the relation will have zero or one distribution column groups. Several column
 *     groups may appear during join. E.g. given A(a) and B(b), after doing {@code A JOIN B on a = b}, resulting
 *     relation will have two distribution column groups: {a} and {b}.</li>
 *     <li>{@code REPLICATED} - the full copy of the whole relation exists on every node. Typically this distribution
 *     is used for replicated maps.</li>
 *     <li>{@code SINGLETON} - the whole relation exists only on a single node. This distribution type is mostly used
 *     for {@link com.hazelcast.sql.impl.calcite.physical.rel.RootPhysicalRel} operator or in case there is only one
 *     member with data in the cluster.</li>
 * </ul>
 */
public class DistributionTrait implements RelTrait {
    /** Data is distributed between nodes, but actual distribution column is unknown. */
    public static final DistributionTrait DISTRIBUTED_DIST = Builder.ofType(DISTRIBUTED).build();

    /** Data is distributed in replicated map. */
    public static final DistributionTrait REPLICATED_DIST = Builder.ofType(REPLICATED).build();

    /** Consume the whole stream on a single node. */
    public static final DistributionTrait SINGLETON_DIST = Builder.ofType(SINGLETON).build();

    /** Distribution without any restriction. */
    public static final DistributionTrait ANY_DIST =  Builder.ofType(ANY).build();

    /** Distribution type. */
    private final DistributionType type;

    /** Distribution fields. */
    private final List<List<DistributionField>> fieldGroups;

    public DistributionTrait(DistributionType type, List<List<DistributionField>> fieldGroups) {
        this.type = type;
        this.fieldGroups = fieldGroups;
    }

    public DistributionType getType() {
        return type;
    }

    public List<List<DistributionField>> getFieldGroups() {
        return fieldGroups;
    }

    public boolean hasFieldGroups() {
        return !fieldGroups.isEmpty();
    }

    @Override
    public RelTraitDef getTraitDef() {
        return DistributionTraitDef.INSTANCE;
    }

    @Override
    public boolean satisfies(RelTrait targetTrait) {
        if (!(targetTrait instanceof DistributionTrait)) {
            return false;
        }

        DistributionTrait targetTrait0 = (DistributionTrait) targetTrait;
        DistributionType targetType = ((DistributionTrait) targetTrait).getType();

        // Any type satisfies ANY.
        if (targetType == ANY) {
            return true;
        }

        // Special handling of DISTRIBUTED-DISTRIBUTED pair.
        if (type == DISTRIBUTED && targetTrait0.getType() == DISTRIBUTED) {
            return satisfiesDistributed(this, targetTrait0);
        }

        // Converting from REPLICATED to SINGLETON is always OK.
        if (type == REPLICATED && targetTrait0.getType() == SINGLETON) {
            return true;
        }

        // If there are no distribution fields, we may consider SINGLETON as a special case of DISTRIBUTED.
        if (type == SINGLETON && targetTrait0.getType() == DISTRIBUTED && !targetTrait0.hasFieldGroups()) {
            return true;
        }

        // Otherwise compare two distributions.
        return this.equals(targetTrait);
    }

    /**
     * Check if the first DISTRIBUTED trait satisfies target DISTRIBUTED trait.
     *
     * @param currentTrait Current trait.
     * @param targetTrait Target trait.
     * @return {@code True} if satisfies, {@code false} if conversion is required.
     */
    private static boolean satisfiesDistributed(DistributionTrait currentTrait, DistributionTrait targetTrait) {
        assert currentTrait.getType() == DISTRIBUTED;
        assert targetTrait.getType() == DISTRIBUTED;

        if (!targetTrait.hasFieldGroups()) {
            // Converting from DISTRIBUTED to unknown DISTRIBUTED is always OK.
            return true;
        } else if (!currentTrait.hasFieldGroups()) {
            // If current distribution doesn't have distribution fields, whilst the other does, conversion if needed.
            return false;
        } else {
            // Otherwise compare every pair of source and target field group.
            for (List<DistributionField> currentGroup : currentTrait.getFieldGroups()) {
                for (List<DistributionField> targetGroup : targetTrait.getFieldGroups()) {
                    if (satisfiesDistributed(currentGroup, targetGroup))
                        return true;
                }
            }

            return false;
        }
    }

    /**
     * Check if current distribution fields satisfies target distribution fields.
     *
     * This is so iff current distribution fields are a subset of target distribution fields. Order is not important.
     * 1) {a, b} satisfies {a, b}    => true
     *    {a, b} satisfies {b, a}    => true
     * 2) {a, b} satisfies {a, b, c} => true
     * 3) {a, b} satisfies {a}       => false
     *
     * @param currentFields Current distribution fields.
     * @param targetFields Target distribution fields.
     *
     * @return {@code True} if satisfies, {@code false} otherwise.
     */
    private static boolean satisfiesDistributed(
        List<DistributionField> currentFields,
        List<DistributionField> targetFields
    ) {
        if (currentFields.size() > targetFields.size())
            return false;

        for (DistributionField currentField : currentFields) {
            if (!targetFields.contains(currentField)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if input of the distribution is complete, i.e. the whole set of tuples is available locally. This holds
     * for SINGLETON distribution (follow from it's definition) and for REPLICATED distribution (all data members
     * has the whole result set).
     *
     * @return {@code True} if distribution is complete, {@code false} otherwise.
     */
    public boolean isComplete() {
        return type == SINGLETON || type == REPLICATED;
    }

    @Override
    public void register(RelOptPlanner planner) {
        // No-op.
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DistributionTrait other = (DistributionTrait) o;

        return type == other.type && fieldGroups.equals(other.fieldGroups);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + fieldGroups.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder res = new StringBuilder(type.name());

        if (fieldGroups.size() == 1) {
            appendFieldGroupToString(res, fieldGroups.get(0));
        } else if (!fieldGroups.isEmpty()) {
            res.append("{");

            for (int i = 0; i < fieldGroups.size(); i++) {
                if (i != 0) {
                    res.append(", ");
                }

                appendFieldGroupToString(res, fieldGroups.get(i));
            }

            res.append("}");
        }

        return res.toString();
    }

    private static void appendFieldGroupToString(StringBuilder builder, List<DistributionField> fields) {
        builder.append("{");

        for (int i = 0; i < fields.size(); i++) {
            if (i != 0) {
                builder.append(", ");
            }

            DistributionField field = fields.get(i);

            builder.append("$").append(field.getIndex());

            if (field.getNestedField() != null) {
                builder.append(".").append(field.getNestedField());
            }
        }

        builder.append("}");
    }

    private static String fieldGroupToString(List<DistributionField> fields) {
        StringBuilder res = new StringBuilder();

        appendFieldGroupToString(res, fields);

        return res.toString();
    }

    public static final class Builder {
        private final DistributionType type;
        private List<List<DistributionField>> fieldGroups;

        private Builder(DistributionType type) {
            assert type != null;

            this.type = type;
        }

        public static Builder ofType(DistributionType type) {
            return new Builder(type);
        }

        public Builder addFieldGroup(DistributionField field) {
            ArrayList<DistributionField> fields = new ArrayList<>(1);

            fields.add(field);

            return addFieldGroup(fields);
        }

        public Builder addFieldGroup(List<DistributionField> fields) {
            assert fields != null;
            assert !fields.isEmpty();

            if (fieldGroups == null) {
                fieldGroups = new ArrayList<>(1);
            }

            fieldGroups.add(Collections.unmodifiableList(new ArrayList<>(fields)));

            return this;
        }

        public DistributionTrait build() {
            if (fieldGroups == null) {
                return new DistributionTrait(type, Collections.emptyList());
            } else {
                assert !fieldGroups.isEmpty();

                // Do not modify original list to allow for builder reuse.
                ArrayList<List<DistributionField>> fieldGroups0 = new ArrayList<>(fieldGroups);

                fieldGroups0.sort(FieldGroupComparator.INSTANCE);

                return new DistributionTrait(type, Collections.unmodifiableList(fieldGroups0));
            }
        }
    }

    /**
     * Field group comparator. Sorts several distribution groups in alphabetical order, so that distribution of
     * {a1, a2} + {b1, b2} is considered equal to {b1, b2} + {a1, a2}.
     */
    private static final class FieldGroupComparator implements Comparator<List<DistributionField>> {
        /** Singleton instance. */
        private static final FieldGroupComparator INSTANCE = new FieldGroupComparator();

        @Override
        public int compare(List<DistributionField> o1, List<DistributionField> o2) {
            String str1 = fieldGroupToString(o1);
            String str2 = fieldGroupToString(o2);

            return str1.compareTo(str2);
        }
    }
}