package com.exascale.optimizer;

public class DropTable extends SQLStatement
{
	private final TableName table;

	public DropTable(final TableName table)
	{
		this.table = table;
	}

	public TableName getTable()
	{
		return table;
	}
}
