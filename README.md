# NaNoWriMo StatTracker

This is a Module for my [Telegram Bot Framework].

## Description
Gets the stats from the NaNoWriMo-Website and generates fancy charts.

## Usages
####`/nano stats [Username]`

Displays the stats of a particular user.

####`/nano graph [Username] [Username]...`

Alias: `/nano chart`

Draws a graph containing the progress of one or more users.

####`/nano compare [Username] [Username]...`

Compares the number of words written today.
Requires at least two users.

## Info

Run `gradle shadowJar` to create a jar. 
It will be put into a new directory called `_working_directory` to easily distinguish files that belong to the project from files that have been created by the bot.

You cannot run the module without the BotController.

There is a test-class that basically just proxies the BotController to make it easier to debug with IntelliJ.



