/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese
 * opensource volunteers. you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Any questions about this component can be directed to it's project Web address
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.config.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.mycat.config.model.rule.RuleConfig;
import io.mycat.util.SplitUtil;

/**
 * @author mycat
 */
public class TableConfig {
	public enum TableTypeEnum{
		TYPE_SHARDING_TABLE, TYPE_GLOBAL_TABLE
	}
	private final String name;
	private final String primaryKey;
	private final boolean autoIncrement;
	private final boolean needAddLimit;
	private final TableTypeEnum tableType;
	private final ArrayList<String> dataNodes;
	private final RuleConfig rule;
	private final String partitionColumn;
	private final boolean ruleRequired;
	private final boolean partionKeyIsPrimaryKey;
	private final Random rand = new Random();
	/**
	 * Child Table
	 */
	private final TableConfig parentTC;
	private final String joinKey;
	private final String parentKey;
	private final String locateRTableKeySql;
	private final TableConfig directRouteTC;

	private volatile Map<String,List<String>> dataNodeTableStructureSQLMap;
	private ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock(false);

	public TableConfig(String name, String primaryKey, boolean autoIncrement, boolean needAddLimit,
			TableTypeEnum tableType, String dataNode, RuleConfig rule, boolean ruleRequired) {
		this(name, primaryKey, autoIncrement, needAddLimit, tableType, dataNode, rule, ruleRequired, null, null, null);
	}

	public TableConfig(String name, String primaryKey, boolean autoIncrement, boolean needAddLimit,
			TableTypeEnum tableType, String dataNode, RuleConfig rule, boolean ruleRequired, TableConfig parentTC,
			String joinKey, String parentKey) {
		if (name == null) {
			throw new IllegalArgumentException("table name is null");
		} else if (dataNode == null) {
			throw new IllegalArgumentException("dataNode name is null");
		}
		this.primaryKey = primaryKey;
		this.autoIncrement = autoIncrement;
		this.needAddLimit=needAddLimit;
		this.tableType = tableType;
		if (ruleRequired && rule == null) {
			throw new IllegalArgumentException("ruleRequired but rule is null");
		}
		this.name = name;
		String theDataNodes[] = SplitUtil.split(dataNode, ',', '$', '-');
		if (theDataNodes == null || theDataNodes.length <= 0) {
			throw new IllegalArgumentException("invalid table dataNodes: " + dataNode + " for table " + name);
		}
		if (tableType != TableTypeEnum.TYPE_GLOBAL_TABLE && parentTC == null && theDataNodes.length > 1 && rule == null) {
			throw new IllegalArgumentException("invalid table dataNodes: " + dataNode + " for table " + name);
		}
		dataNodes = new ArrayList<String>(theDataNodes.length);
		for (String dn : theDataNodes) {
			dataNodes.add(dn);
		}
		this.rule = rule;
		this.partitionColumn = (rule == null) ? null : rule.getColumn();
		partionKeyIsPrimaryKey = (partitionColumn == null) ? primaryKey == null : partitionColumn.equals(primaryKey);
		this.ruleRequired = ruleRequired;
		this.parentTC = parentTC;
		if (parentTC != null) {
			this.joinKey = joinKey;
			this.parentKey = parentKey;
			if (parentTC.getParentTC() == null) {
				if (parentKey.equals(parentTC.partitionColumn)) {
					// secondLevel ,parentKey==parent.partitionColumn
					directRouteTC = parentTC;
					locateRTableKeySql = null;
				} else {
					directRouteTC = null;
					locateRTableKeySql = genLocateRootParentSQL();
				}
			} else if (parentTC.getDirectRouteTC() != null) {
				/**
				 * grandTable partitionColumn =col1
				 * fatherTable joinkey =col2,parentkey = col1....so directRouteTC = grandTable
				 * thisTable joinkey = col3 ,parentkey = col2...so directRouteTC = grandTable
				 */
				if(parentKey.equals(parentTC.joinKey)){
					directRouteTC = parentTC.getDirectRouteTC();
					locateRTableKeySql = null;
				}
				else{
					directRouteTC = null;
					locateRTableKeySql = genLocateRootParentSQL();
				}
			} else {
				directRouteTC = null;
				locateRTableKeySql = genLocateRootParentSQL();
			}
		} else {
			this.joinKey = null;
			this.parentKey = null;
			locateRTableKeySql = null;
			directRouteTC = this;
		}
	}

	public String getPrimaryKey() {
		return primaryKey;
	}

    public boolean isAutoIncrement() {
		return autoIncrement;
	}

	public boolean isNeedAddLimit() {
		return needAddLimit;
	}

	public TableConfig getDirectRouteTC() {
		return directRouteTC;
	}

	public String getLocateRTableKeySql() {
		return locateRTableKeySql;
	}

	public boolean isGlobalTable() {
		return this.tableType == TableTypeEnum.TYPE_GLOBAL_TABLE;
	}

	public String genLocateRootParentSQL() {
		TableConfig tb = this;
		StringBuilder tableSb = new StringBuilder();
		StringBuilder condition = new StringBuilder();
		TableConfig prevTC = null;
		int level = 0;
		String latestCond = null;
		while (tb.parentTC != null) {
			tableSb.append(tb.parentTC.name).append(',');
			String relation = null;
			if (level == 0) {
				latestCond = " " + tb.parentTC.getName() + '.' + tb.parentKey
						+ "=";
			} else {
				relation = tb.parentTC.getName() + '.' + tb.parentKey + '='
						+ tb.name + '.' + tb.joinKey;
				condition.append(relation).append(" AND ");
			}
			level++;
			prevTC = tb;
			tb = tb.parentTC;
		}
		String sql = "SELECT "
				+ prevTC.parentTC.name
				+ '.'
				+ prevTC.parentKey
				+ " FROM "
				+ tableSb.substring(0, tableSb.length() - 1)
				+ " WHERE "
				+ ((level < 2) ? latestCond : condition.toString() + latestCond);
		// System.out.println(this.name+" sql " + sql);
		return sql;

	}

	public String getPartitionColumn() {
		return partitionColumn;
	}

	public TableTypeEnum getTableType() {
		return tableType;
	}

	/**
	 * get root parent
	 *
	 * @return
	 */
	public TableConfig getRootParent() {
		if (parentTC == null) {
			return null;
		}
		TableConfig preParent = parentTC;
		TableConfig parent = preParent.getParentTC();

		while (parent != null) {
			preParent = parent;
			parent = parent.getParentTC();
		}
		return preParent;
	}

	public TableConfig getParentTC() {
		return parentTC;
	}

	public String getJoinKey() {
		return joinKey;
	}

	public String getParentKey() {
		return parentKey;
	}
	public String getName() {
		return name;
	}

	public ArrayList<String> getDataNodes() {
		return dataNodes;
	}

	public String getRandomDataNode() {
		int index = Math.abs(rand.nextInt(Integer.MAX_VALUE)) % dataNodes.size();
		return dataNodes.get(index);
	}

	public boolean isRuleRequired() {
		return ruleRequired;
	}

	public RuleConfig getRule() {
		return rule;
	}

	public boolean primaryKeyIsPartionKey() {
		return partionKeyIsPrimaryKey;
	}


	public ReentrantReadWriteLock getReentrantReadWriteLock() {
		return reentrantReadWriteLock;
	}

	public void setReentrantReadWriteLock(ReentrantReadWriteLock reentrantReadWriteLock) {
		this.reentrantReadWriteLock = reentrantReadWriteLock;
	}

	public Map<String, List<String>> getDataNodeTableStructureSQLMap() {
		return dataNodeTableStructureSQLMap;
	}

	public void setDataNodeTableStructureSQLMap(Map<String, List<String>> dataNodeTableStructureSQLMap) {
		this.dataNodeTableStructureSQLMap = dataNodeTableStructureSQLMap;
	}
}