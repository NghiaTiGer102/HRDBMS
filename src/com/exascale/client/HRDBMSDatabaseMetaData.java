package com.exascale.client;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import com.exascale.filesystem.Page;

public class HRDBMSDatabaseMetaData implements DatabaseMetaData
{
	private final Connection conn;

	public HRDBMSDatabaseMetaData(final Connection conn)
	{
		this.conn = conn;
	}

	@Override
	public boolean allProceduresAreCallable() throws SQLException
	{
		return false;
	}

	@Override
	public boolean allTablesAreSelectable() throws SQLException
	{
		return true;
	}

	@Override
	public boolean autoCommitFailureClosesAllResultSets() throws SQLException
	{
		return true;
	}

	@Override
	public boolean dataDefinitionCausesTransactionCommit() throws SQLException
	{
		return false;
	}

	@Override
	public boolean dataDefinitionIgnoredInTransactions() throws SQLException
	{
		return false;
	}

	@Override
	public boolean deletesAreDetected(final int type) throws SQLException
	{
		return false;
	}

	@Override
	public boolean doesMaxRowSizeIncludeBlobs() throws SQLException
	{
		return true;
	}

	@Override
	public boolean generatedKeyAlwaysReturned() throws SQLException
	{
		return false;
	}

	@Override
	public ResultSet getAttributes(final String catalog, final String schemaPattern, final String typeNamePattern, final String attributeNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getBestRowIdentifier(final String catalog, final String schema, final String table, final int scope, final boolean nullable) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getCatalogs() throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getCatalogSeparator() throws SQLException
	{
		return ".";
	}

	@Override
	public String getCatalogTerm() throws SQLException
	{
		return "schema";
	}

	@Override
	public ResultSet getClientInfoProperties() throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getColumnPrivileges(final String catalog, final String schema, final String table, final String columnNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getColumns(final String catalog, final String schemaPattern, final String tableNamePattern, final String columnNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Connection getConnection() throws SQLException
	{
		return conn;
	}

	@Override
	public ResultSet getCrossReference(final String parentCatalog, final String parentSchema, final String parentTable, final String foreignCatalog, final String foreignSchema, final String foreignTable) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getDatabaseMajorVersion() throws SQLException
	{
		return 1;
	}

	@Override
	public int getDatabaseMinorVersion() throws SQLException
	{
		return 0;
	}

	@Override
	public String getDatabaseProductName() throws SQLException
	{
		return "HRDBMS";
	}

	@Override
	public String getDatabaseProductVersion() throws SQLException
	{
		return "1.0";
	}

	@Override
	public int getDefaultTransactionIsolation() throws SQLException
	{
		return Connection.TRANSACTION_READ_COMMITTED;
	}

	@Override
	public int getDriverMajorVersion()
	{
		return 1;
	}

	@Override
	public int getDriverMinorVersion()
	{
		return 0;
	}

	@Override
	public String getDriverName() throws SQLException
	{
		return "HRDBMS JDBC Driver";
	}

	@Override
	public String getDriverVersion() throws SQLException
	{
		return "1.0";
	}

	@Override
	public ResultSet getExportedKeys(final String catalog, final String schema, final String table) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getExtraNameCharacters() throws SQLException
	{
		return "";
	}

	@Override
	public ResultSet getFunctionColumns(final String catalog, final String schemaPattern, final String functionNamePattern, final String columnNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getFunctions(final String catalog, final String schemaPattern, final String functionNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getIdentifierQuoteString() throws SQLException
	{
		return "'";
	}

	@Override
	public ResultSet getImportedKeys(final String catalog, final String schema, final String table) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getIndexInfo(final String catalog, final String schema, final String table, final boolean unique, final boolean approximate) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getJDBCMajorVersion() throws SQLException
	{
		return 4;
	}

	@Override
	public int getJDBCMinorVersion() throws SQLException
	{
		return 1;
	}

	@Override
	public int getMaxBinaryLiteralLength() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxCatalogNameLength() throws SQLException
	{
		return 128;
	}

	@Override
	public int getMaxCharLiteralLength() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxColumnNameLength() throws SQLException
	{
		return 128;
	}

	@Override
	public int getMaxColumnsInGroupBy() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxColumnsInIndex() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxColumnsInOrderBy() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxColumnsInSelect() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxColumnsInTable() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxConnections() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxCursorNameLength() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxIndexLength() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxProcedureNameLength() throws SQLException
	{
		return 128;
	}

	@Override
	public int getMaxRowSize() throws SQLException
	{
		return Page.BLOCK_SIZE;
	}

	@Override
	public int getMaxSchemaNameLength() throws SQLException
	{
		return 128;
	}

	@Override
	public int getMaxStatementLength() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxStatements() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxTableNameLength() throws SQLException
	{
		return 128;
	}

	@Override
	public int getMaxTablesInSelect() throws SQLException
	{
		return 0;
	}

	@Override
	public int getMaxUserNameLength() throws SQLException
	{
		return 32;
	}

	@Override
	public String getNumericFunctions() throws SQLException
	{
		return "";
	}

	@Override
	public ResultSet getPrimaryKeys(final String catalog, final String schema, final String table) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getProcedureColumns(final String catalog, final String schemaPattern, final String procedureNamePattern, final String columnNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getProcedures(final String catalog, final String schemaPattern, final String procedureNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getProcedureTerm() throws SQLException
	{
		return "stored procedure";
	}

	@Override
	public ResultSet getPseudoColumns(final String catalog, final String schemaPattern, final String tableNamePattern, final String columnNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getResultSetHoldability() throws SQLException
	{
		return ResultSet.CLOSE_CURSORS_AT_COMMIT;
	}

	@Override
	public RowIdLifetime getRowIdLifetime() throws SQLException
	{
		return RowIdLifetime.ROWID_UNSUPPORTED;
	}

	@Override
	public ResultSet getSchemas() throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getSchemas(final String catalog, final String schemaPattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSchemaTerm() throws SQLException
	{
		return "schema";
	}

	@Override
	public String getSearchStringEscape() throws SQLException
	{
		return "";
	}

	@Override
	public String getSQLKeywords() throws SQLException
	{
		return "";
	}

	@Override
	public int getSQLStateType() throws SQLException
	{
		return 0;
	}

	@Override
	public String getStringFunctions() throws SQLException
	{
		return "";
	}

	@Override
	public ResultSet getSuperTables(final String catalog, final String schemaPattern, final String tableNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getSuperTypes(final String catalog, final String schemaPattern, final String typeNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSystemFunctions() throws SQLException
	{
		return "";
	}

	@Override
	public ResultSet getTablePrivileges(final String catalog, final String schemaPattern, final String tableNamePattern) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getTables(final String catalog, final String schemaPattern, final String tableNamePattern, final String[] types) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getTableTypes() throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getTimeDateFunctions() throws SQLException
	{
		return "";
	}

	@Override
	public ResultSet getTypeInfo() throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getUDTs(final String catalog, final String schemaPattern, final String typeNamePattern, final int[] types) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getURL() throws SQLException
	{
		return null;
	}

	@Override
	public String getUserName() throws SQLException
	{
		return "";
	}

	@Override
	public ResultSet getVersionColumns(final String catalog, final String schema, final String table) throws SQLException
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean insertsAreDetected(final int type) throws SQLException
	{
		return false;
	}

	@Override
	public boolean isCatalogAtStart() throws SQLException
	{
		return true;
	}

	@Override
	public boolean isReadOnly() throws SQLException
	{
		return false;
	}

	@Override
	public boolean isWrapperFor(final Class<?> iface) throws SQLException
	{
		return false;
	}

	@Override
	public boolean locatorsUpdateCopy() throws SQLException
	{
		return false;
	}

	@Override
	public boolean nullPlusNonNullIsNull() throws SQLException
	{
		return true;
	}

	@Override
	public boolean nullsAreSortedAtEnd() throws SQLException
	{
		return false;
	}

	@Override
	public boolean nullsAreSortedAtStart() throws SQLException
	{
		return false;
	}

	@Override
	public boolean nullsAreSortedHigh() throws SQLException
	{
		return true;
	}

	@Override
	public boolean nullsAreSortedLow() throws SQLException
	{
		return false;
	}

	@Override
	public boolean othersDeletesAreVisible(final int type) throws SQLException
	{
		return false;
	}

	@Override
	public boolean othersInsertsAreVisible(final int type) throws SQLException
	{
		return false;
	}

	@Override
	public boolean othersUpdatesAreVisible(final int type) throws SQLException
	{
		return false;
	}

	@Override
	public boolean ownDeletesAreVisible(final int type) throws SQLException
	{
		return true;
	}

	@Override
	public boolean ownInsertsAreVisible(final int type) throws SQLException
	{
		return true;
	}

	@Override
	public boolean ownUpdatesAreVisible(final int type) throws SQLException
	{
		return true;
	}

	@Override
	public boolean storesLowerCaseIdentifiers() throws SQLException
	{
		return false;
	}

	@Override
	public boolean storesLowerCaseQuotedIdentifiers() throws SQLException
	{
		return false;
	}

	@Override
	public boolean storesMixedCaseIdentifiers() throws SQLException
	{
		return false;
	}

	@Override
	public boolean storesMixedCaseQuotedIdentifiers() throws SQLException
	{
		return false;
	}

	@Override
	public boolean storesUpperCaseIdentifiers() throws SQLException
	{
		return true;
	}

	@Override
	public boolean storesUpperCaseQuotedIdentifiers() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsAlterTableWithAddColumn() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsAlterTableWithDropColumn() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsANSI92EntryLevelSQL() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsANSI92FullSQL() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsANSI92IntermediateSQL() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsBatchUpdates() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsCatalogsInDataManipulation() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsCatalogsInIndexDefinitions() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsCatalogsInProcedureCalls() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsCatalogsInTableDefinitions() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsColumnAliasing() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsConvert() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsConvert(final int fromType, final int toType) throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsCoreSQLGrammar() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsCorrelatedSubqueries() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsDataManipulationTransactionsOnly() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsDifferentTableCorrelationNames() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsExpressionsInOrderBy() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsExtendedSQLGrammar() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsFullOuterJoins() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsGetGeneratedKeys() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsGroupBy() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsGroupByBeyondSelect() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsGroupByUnrelated() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsIntegrityEnhancementFacility() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsLikeEscapeClause() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsLimitedOuterJoins() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsMinimumSQLGrammar() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsMixedCaseIdentifiers() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsMultipleOpenResults() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsMultipleResultSets() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsMultipleTransactions() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsNamedParameters() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsNonNullableColumns() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsOpenCursorsAcrossCommit() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsOpenCursorsAcrossRollback() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsOpenStatementsAcrossCommit() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsOpenStatementsAcrossRollback() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsOrderByUnrelated() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsOuterJoins() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsPositionedDelete() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsPositionedUpdate() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsResultSetConcurrency(final int type, final int concurrency) throws SQLException
	{
		if (type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY)
		{
			return true;
		}

		return false;
	}

	@Override
	public boolean supportsResultSetHoldability(final int holdability) throws SQLException
	{
		if (holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT)
		{
			return true;
		}

		return false;
	}

	@Override
	public boolean supportsResultSetType(final int type) throws SQLException
	{
		if (type == ResultSet.TYPE_FORWARD_ONLY)
		{
			return true;
		}

		return false;
	}

	@Override
	public boolean supportsSavepoints() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsSchemasInDataManipulation() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsSchemasInIndexDefinitions() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsSchemasInProcedureCalls() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsSchemasInTableDefinitions() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsSelectForUpdate() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsStatementPooling() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsStoredProcedures() throws SQLException
	{
		return false;
	}

	@Override
	public boolean supportsSubqueriesInComparisons() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsSubqueriesInExists() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsSubqueriesInIns() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsSubqueriesInQuantifieds() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsTableCorrelationNames() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsTransactionIsolationLevel(final int level) throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsTransactions() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsUnion() throws SQLException
	{
		return true;
	}

	@Override
	public boolean supportsUnionAll() throws SQLException
	{
		return true;
	}

	@Override
	public <T> T unwrap(final Class<T> iface) throws SQLException
	{
		throw new SQLException("Unwrap() is not supported.");
	}

	@Override
	public boolean updatesAreDetected(final int type) throws SQLException
	{
		return false;
	}

	@Override
	public boolean usesLocalFilePerTable() throws SQLException
	{
		return false;
	}

	@Override
	public boolean usesLocalFiles() throws SQLException
	{
		return false;
	}

}
