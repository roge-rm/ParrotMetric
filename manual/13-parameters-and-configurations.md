# Parameters and configurations
> Named sizes, and versions of a part.

## Parameters

**Parameters**, under **Inspect**, is a table of named values. **Add a parameter** gives one a name and a value, which can itself use others, like `wall*2`. A name is letters, digits and `_`, starting with a letter.

Any size in a sketch or a tool can then use the names. Change a parameter and everything that uses it changes, so a box can be made bigger by changing one number.

**Save as CSV** saves the table as a CSV file with each parameter's name, expression and value, to keep or change in a spreadsheet. **Read CSV** reads one back: parameters with the same name take its expression, and new names are added. A row with no expression takes its value. The first row can be a heading, and the cells can be split by commas or semicolons.

## Configurations

A configuration keeps a set of parameter values and which steps are turned off, under a name. Use them for versions of a part, like a small and a large size, or with and without a lid.

At the top of the Parameters table, **Save as a configuration** keeps the current values and switched off steps under a name. Tap a configuration's chip to switch to it. With one chosen, **Update** saves your changes back to it, **Save as new** keeps them as another one, and the delete button beside them takes it away.
