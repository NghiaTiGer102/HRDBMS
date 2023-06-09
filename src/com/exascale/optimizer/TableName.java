package com.exascale.optimizer;

public class TableName
{
	private String schema;
	private final String name;

	public TableName(final String name)
	{
		this.name = name;
	}

	public TableName(final String schema, final String name)
	{
		this.schema = schema;
		this.name = name;
	}

	@Override
	public TableName clone()
	{
		return new TableName(schema, name);
	}

	public String getName()
	{
		return name;
	}

	public String getSchema()
	{
		return schema;
	}
}
