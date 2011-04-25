package org.mobicents.slee.resource.jdbc.event;

import java.sql.Statement;

/**
 * 
 * @author martins
 * 
 */
public class StatementUnknownResultEventImpl extends AbstractStatementEvent
		implements StatementUnknownResultEvent {

	/**
	 * 
	 */
	private final boolean executionResult;

	/**
	 * 
	 * @param autoGeneratedKeys
	 * @param columnIndexes
	 * @param columnNames
	 * @param sql
	 * @param statement
	 * @param executionResult
	 */
	public StatementUnknownResultEventImpl(Integer autoGeneratedKeys,
			int[] columnIndexes, String[] columnNames, String sql,
			Statement statement, boolean executionResult) {
		super(autoGeneratedKeys, columnIndexes, columnNames, sql, statement);
		this.executionResult = executionResult;
	}

	@Override
	public boolean getExecutionResult() {
		return executionResult;
	}

}