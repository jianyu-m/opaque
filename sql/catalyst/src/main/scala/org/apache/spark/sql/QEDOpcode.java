/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql;

public enum QEDOpcode {
    OP_BD1(11),
    OP_BD2(10),
    OP_SORT_INTEGERS_TEST(90),
    OP_SORT_COL1(2),
    OP_SORT_COL2(50),
    OP_SORT_COL3_IS_DUMMY_COL1(52),
    OP_SORT_COL4_IS_DUMMY_COL2(51),
    OP_GROUPBY_COL1_SUM_COL2_STEP1(102),
    OP_GROUPBY_COL1_SUM_COL2_STEP2(103),
    OP_GROUPBY_COL2_SUM_COL3_STEP1(1),
    OP_GROUPBY_COL2_SUM_COL3_STEP2(101),
    OP_GROUPBY_COL1_AVG_COL2_SUM_COL3_STEP1(104),
    OP_GROUPBY_COL1_AVG_COL2_SUM_COL3_STEP2(105),
    OP_JOIN_COL1(106),
    OP_JOIN_COL2(3),
    OP_FILTER_COL2_GT3(30),
    OP_FILTER_TEST(91),
    OP_FILTER_COL3_NOT_DUMMY(33),
    OP_FILTER_COL4_NOT_DUMMY(32),
    OP_FILTER_COL1_DATE_BETWEEN_1980_01_01_AND_1980_04_01(34),
    OP_PROJECT_PAGERANK_WEIGHT_RANK(35),
    OP_PROJECT_PAGERANK_APPLY_INCOMING_RANK(36),
    OP_JOIN_PAGERANK(37);

    private int _value;

    private QEDOpcode(int _value) {
        this._value = _value;
    }

    public int value() {
        return _value;
    }

    public boolean isJoin() {
        return this == OP_JOIN_COL1 || this == OP_JOIN_COL2 || this == OP_JOIN_PAGERANK;
    }
}
