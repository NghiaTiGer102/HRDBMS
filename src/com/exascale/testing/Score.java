package com.exascale.testing;

import java.util.ArrayList;
import java.util.HashSet;

public class Score
{
	public static void main(final String[] args)
	{
		final ArrayList<Integer> columns = new ArrayList<Integer>();
		columns.add(0);
		columns.add(1);
		columns.add(2);
		columns.add(3);
		columns.add(4);
		columns.add(5);
		columns.add(6);
		columns.add(7);
		columns.add(8);
		ArrayList<ArrayList<Integer>> accesses = new ArrayList<ArrayList<Integer>>();
		ArrayList<Integer> access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(4);
		access.add(7);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(4);
		access.add(5);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(5);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(3);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		accesses.add(access);

		int score = score(columns, accesses);
		System.out.println("Original score for ORDERS was " + score);
		columns.clear();
		columns.add(2);
		columns.add(3);
		columns.add(0);
		columns.add(1);
		columns.add(4);
		columns.add(5);
		columns.add(6);
		columns.add(7);
		columns.add(8);

		score = score(columns, accesses);
		System.out.println("New score for ORDERS is " + score);

		columns.clear();
		columns.add(0);
		columns.add(1);
		columns.add(2);
		columns.add(3);
		columns.add(4);
		columns.add(5);
		columns.add(6);
		columns.add(7);
		columns.add(8);
		columns.add(9);
		columns.add(10);
		columns.add(11);
		columns.add(12);
		columns.add(13);
		columns.add(14);
		columns.add(15);

		accesses = new ArrayList<ArrayList<Integer>>();
		access = new ArrayList<Integer>();
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(7);
		access.add(8);
		access.add(9);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(11);
		access.add(12);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(5);
		access.add(6);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(2);
		access.add(5);
		access.add(6);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(2);
		access.add(4);
		access.add(5);
		access.add(6);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(5);
		access.add(6);
		access.add(8);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(10);
		access.add(11);
		access.add(12);
		access.add(14);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(2);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(2);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(4);
		access.add(5);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(13);
		access.add(14);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(2);
		access.add(4);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(11);
		access.add(12);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(11);
		access.add(12);
		accesses.add(access);

		score = score(columns, accesses);
		System.out.println("Original score for LINEITEM was " + score);

		accesses.clear();
		columns.clear();
		columns.add(0);
		columns.add(1);
		columns.add(2);
		columns.add(3);
		columns.add(4);
		columns.add(5);
		columns.add(6);
		columns.add(7);
		columns.add(8);
		columns.add(9);
		columns.add(10);
		columns.add(11);
		columns.add(12);
		columns.add(13);
		columns.add(14);
		columns.add(15);

		access = new ArrayList<Integer>();
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(7);
		access.add(8);
		access.add(9);
		access.add(10);
		accesses.add(access);

		score = score(columns, accesses);
		System.out.println("Original score for LINEITEM(7) was " + score);

		accesses.clear();
		columns.clear();
		columns.add(5);
		columns.add(6);
		columns.add(2);
		columns.add(0);
		columns.add(11);
		columns.add(12);
		columns.add(14);
		columns.add(13);
		columns.add(15);
		columns.add(9);
		columns.add(7);
		columns.add(8);
		columns.add(1);
		columns.add(4);
		columns.add(10);
		columns.add(3);

		access = new ArrayList<Integer>();
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(7);
		access.add(8);
		access.add(9);
		access.add(10);
		accesses.add(access);

		score = score(columns, accesses);
		System.out.println("New score for LINEITEM(7) was " + score);
	}

	private static int score(final ArrayList<Integer> order, final ArrayList<ArrayList<Integer>> accesses)
	{
		final ArrayList<Integer> disk = new ArrayList<Integer>();
		disk.add(-1);
		disk.addAll(order);
		int copies = 1;

		if (disk.size() % 3 == 1)
		{
			disk.addAll(order);
			copies++;
		}
		else
		{
			while (disk.size() % 3 != 1)
			{
				disk.addAll(order);
				copies++;
			}
		}

		int score = 0;
		for (final ArrayList<Integer> access : accesses)
		{
			final HashSet<Integer> sbs = new HashSet<Integer>();
			for (final int col : access)
			{
				int found = 0;
				int i = 1;
				while (found < copies)
				{
					try
					{
						if (disk.get(i) == col)
						{
							final int sb = i / 3;
							sbs.add(sb);
							found++;
						}
					}
					catch (final Exception e)
					{
						System.out.println("Looking for " + col + " in " + disk);
						System.out.println("Found " + found + " instances");
						System.out.println("But there should be " + copies);
					}

					i++;
				}
			}

			score += sbs.size();
		}

		return score;
	}
}
