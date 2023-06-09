package com.exascale.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import com.exascale.client.HRDBMSStatement;

public class RemoveTest
{
	public static void main(final String[] args) throws Exception
	{
		int i = 0;
		while (i < Integer.parseInt(args[0]))
		{
			new ExecuteThread().start();
			i++;
		}
	}

	private static class ExecuteThread extends Thread
	{
		private Connection conn;

		@Override
		public void run()
		{
			try
			{
				final long start = System.currentTimeMillis();
				Class.forName("com.exascale.client.HRDBMSDriver");
				conn = DriverManager.getConnection("jdbc:hrdbms://192.168.56.104:3232");
				conn.setAutoCommit(false);

				final HRDBMSStatement stmt = (HRDBMSStatement)conn.createStatement();

				int i = 0;
				while (i < 1000)
				{
					stmt.remove("JASON.TEST2", i);
					i++;
					if (i % 100 == 0)
					{
						System.out.println(i);
					}
				}

				final long end1 = System.currentTimeMillis();

				stmt.close();
				conn.close();
				long seconds1 = (end1 - start) / 1000;
				final long minutes1 = seconds1 / 60;
				seconds1 -= (minutes1 * 60);

				System.out.println("Remove test took " + minutes1 + " minutes and " + seconds1 + " seconds.");
			}
			catch (final Exception e)
			{
				e.printStackTrace();
			}
		}
	}
}
