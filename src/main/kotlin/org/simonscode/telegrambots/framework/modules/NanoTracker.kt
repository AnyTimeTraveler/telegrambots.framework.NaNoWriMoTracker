package org.simonscode.telegrambots.framework.modules

import org.jsoup.Jsoup
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.style.Styler.LegendPosition
import org.knowm.xchart.style.colors.ChartColor
import org.knowm.xchart.style.colors.XChartSeriesColors
import org.knowm.xchart.style.lines.SeriesLines
import org.knowm.xchart.style.markers.SeriesMarkers
import org.simonscode.telegrambots.framework.Bot
import org.simonscode.telegrambots.framework.Module
import org.simonscode.telegrambots.framework.Utils
import org.telegram.telegrambots.api.methods.send.SendMessage
import org.telegram.telegrambots.api.methods.send.SendPhoto
import org.telegram.telegrambots.api.objects.Update
import java.awt.Color
import java.awt.Font
import java.io.File
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale
import kotlin.collections.HashMap


class NanoTracker : Module {
    private class DataGrabber(private val tracker: NanoTracker, private val force: Boolean) : TimerTask() {
        private val users = listOf("myrrany", "nander", "saegaroth", "jmking80")
        private val behind = mutableListOf<String>()
        override fun run() {
            users.map { it to tracker.getNovelStats(it) }.forEach { (a, b) ->
                if (force && tracker.bot != null && tracker.chatId != null) {
                    if (Integer.parseInt(b?.get("Words Per Day To Finish On Time")?.replace(",", "")) > 1667) {
                        behind.add(a)
                    }
                }
                tracker.addStats(force, a, b)
            }
            if (force && behind.isNotEmpty()) {
                tracker.bot!!.execute(SendMessage().setChatId(tracker.chatId).setText("Some people have fallen a bit behind,\nbut it's still possible to finish on time! Go, " + behind.joinToString(", ") + "!"))
                behind.clear()
            }
        }
    }

    override val name: String
        get() = "NaNoWriMoStatTracker"
    override val version: String
        get() = "2.0"

    private var bot: Bot? = null
    private var chatId: Long? = null

    private var state: MutableMap<String, MutableMap<Date, Int>>? = null
    private val dataGrabber = DataGrabber(this, false)
    private val dataGrabberTimer = Timer()

    override fun getHelpText(args: Array<String>?): String? {
        return "Description:\n" +
                "Gets the stats from the NaNoWriMo-Website, so you don't have to bother.\n" +
                "Usages:\n" +
                "/nano stats [Username]\n" +
                "    Displays the stats of a particular user.\n" +
                "/nano graph [Username] [Username]...\n" +
                "Alias: /nano chart\n" +
                "    Draws a graph containing the progress of one or more users.\n" +
                "/nano compare [Username] [Username]...\n" +
                "    Compares the number of words written today.\n" +
                "    Requires at least two users."
    }

    override fun saveState(): Any? {
        dataGrabberTimer.cancel()
        return state
    }

    override fun setup(state: Any?) {
        @Suppress("UNCHECKED_CAST")
        if (state != null && state is MutableMap<*, *>) {
            val sdf = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy")
            this.state = (state as MutableMap<String, MutableMap<String, Double>>).
                    map { (a, b) ->
                        a to b.map { (c, d) -> sdf.parse(c) to d.toInt() }.
                                toMap().toMutableMap()
                    }.toMap().toMutableMap()
        } else {
            this.state = HashMap()
        }
        startTimers()
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun startTimers() {
        dataGrabberTimer.scheduleAtFixedRate(dataGrabber, 10_000, 15 * 60 * 1000)
        val c = Calendar.getInstance()
        c.setTime(Date())
        c.set(Calendar.HOUR_OF_DAY, 23)
        c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 30)
        dataGrabberTimer.scheduleAtFixedRate(DataGrabber(this, true), c.getTime(), 24 * 60 * 60 * 1000)
    }

    override fun processUpdate(sender: Bot, update: Update) {
        val message = Utils.getMessageFromUpdate(update, true) ?: return
        if (!message.hasText())
            return
        val args = message.text.split(" ")
        if (args[0] != "/nano")
            return
        if (args.size <= 1) {
            Utils.send(sender, update, getHelpText(null)!!)
            return
        }
        var users = args.subList(2, args.size)
        if (users.isEmpty())
            users = listOf("myrrany", "nander", "saegaroth", "jmking80")
        when (args[1]) {
            "stats" -> {
                if (args.size == 3) {
                    val sb = StringBuilder()
                    val stats = getNovelStats(args[2]) ?: return
                    stats.remove("wordgoal")
                    stats.remove("chart")
                    stats.remove("Target Word Count")
                    stats.remove("Current Day")
                    stats.remove("Days Remaining")

                    for ((key, value) in stats) {
                        sb.append(key)
                        sb.append(" : ")
                        sb.append(value)
                        sb.append("\n")
                    }
                    Utils.reply(sender, message, sb.toString())
                } else {
                    Utils.reply(sender, update, "No user given!\nUse: /nano stats (username)")
                }
            }
            "graph", "chart" -> {
                val file = File("words.png")
                try {
                    dataGrabber.run()
                    getChart(file, users)
                    sender.sendPhoto(SendPhoto().setNewPhoto(file).setCaption("Stats for " + users.joinToString(", ")).setChatId(message.chatId))
                } catch (e: Exception) {
                    Utils.send(sender, message, "Error retrieving data: " + e.localizedMessage)
                    e.printStackTrace()
                }
            }
            "compare" -> Utils.reply(sender, message, compare(users))
            "help" -> Utils.reply(sender, message, getHelpText(null)!!)
//            "set" -> {
//                if (args.size < 3) {
//                    Utils.reply(sender, message, "Fuck you! @#%#$@^#$%#$$~!@$#!@%%$!")
//                    return
//                }
//                when (args[2]) {
//                    "channel" -> {
//                        if (bot == null || chatId == null) {
//                            bot = sender
//                            chatId = message.chatId
//                            Utils.reply(sender, message, "Got it!")
//                        }
//                    }
//                }
//            }
            else -> Utils.reply(sender, message, getHelpText(null)!!)
        }
    }

    private fun compare(users: List<String>): String {
        val stats = users.map { getNovelStats(it) }

        val comparableAspects = listOf("Words Written Today", "Words Per Day To Finish On Time", "Total Words Written")

        val sb = StringBuilder()
        sb.append("\n")
        for (aspect in comparableAspects) {
            compareAspect(aspect, sb, users, stats)
            sb.append("\n")
        }

        val finishOnDates = stats.mapNotNull { it?.get("At This Rate You Will Finish On") }
        val wordsToFinishDates = stats.mapIndexedNotNull { i, it -> users[i] to (Integer.parseInt(it?.get("Total Words Written")?.replace(",", "")) to finishOnDates[i]) }.sortedByDescending { (_, value) -> value.first }

        sb.append("At This Rate You Will Finish On:\n")
        for (i in wordsToFinishDates) {
            sb.append(i.first)
            sb.append(" : ")
            sb.append(i.second.second)
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun compareAspect(aspect: String, sb: StringBuilder, users: List<String>, stats: List<Map<String, String>?>) {
        val absoluteList = stats.mapIndexedNotNull { i, it -> users[i] to Integer.parseInt(it?.get(aspect)?.replace(",", "")) }.toList().sortedByDescending { (_, value) -> value }

        sb.append(aspect)
        sb.append(":\n")
        for (i in absoluteList) {
            sb.append(i.first)
            sb.append(" : ")
            sb.append(i.second)
            sb.append(" : ")
            sb.append(i.second / absoluteList.first().second.toDouble())
            sb.append("\n")
        }
    }

    fun getNovelStats(user: String): MutableMap<String, String>? {
        return try {
            val doc = Jsoup.parse(URL("http://nanowrimo.org/participants/$user/stats"), 5000)
            val stats = doc.getElementById("novel_stats")
            stats.children().map { it.child(0).text() to it.child(1).text() }.toMap().toMutableMap()
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private val peopleMap: Map<String, Pair<String, Color>> = mapOf(
            "nander" to ("Nander" to XChartSeriesColors.ORANGE),
            "myrrany" to ("Myrthe" to XChartSeriesColors.BLUE),
            "jmking80" to ("Jaimie" to XChartSeriesColors.MAGENTA),
            "saegaroth" to ("Jonah" to XChartSeriesColors.GREEN)
    )

    @Suppress("UsePropertyAccessSyntax")
    private fun generateChart(outputFile: File, wordsPerDay: Map<String, Map<Date, Int>>) {
        // Create Chart
        val chart = XYChartBuilder().width(1280).height(960).title("Wordcount").xAxisTitle("Time").yAxisTitle("Words").build()

        // Customize Chart
        chart.styler.plotBackgroundColor = ChartColor.getAWTColor(ChartColor.GREY)
        chart.styler.plotGridLinesColor = Color.WHITE
        chart.styler.chartBackgroundColor = Color.WHITE
        chart.styler.legendBackgroundColor = Color.WHITE
        chart.styler.chartFontColor = Color.BLACK
        chart.styler.chartTitleBoxBackgroundColor = Color(125, 167, 245)
        chart.styler.isChartTitleBoxVisible = false
        chart.styler.chartTitleBoxBorderColor = Color.BLACK
        chart.styler.isPlotGridLinesVisible = true

        chart.styler.axisTickPadding = 25
        chart.styler.axisTickMarkLength = 22
        chart.styler.plotMargin = 0

        chart.styler.chartTitleFont = Font(Font.MONOSPACED, Font.BOLD, 30)
        chart.styler.legendFont = Font(Font.SERIF, Font.PLAIN, 22)
        chart.styler.legendPosition = LegendPosition.InsideSE
        chart.styler.legendSeriesLineLength = 15
        chart.styler.axisTitleFont = Font(Font.SANS_SERIF, Font.BOLD, 22)
        chart.styler.axisTickLabelsFont = Font(Font.SERIF, Font.PLAIN, 18)
        chart.styler.datePattern = "'Day' d"
        chart.styler.decimalPattern = "###,###"
        chart.styler.locale = Locale.GERMAN

        val now = Date()
        val sortedWordsPerDay = wordsPerDay.toList().sortedByDescending { (_, b) -> b.toList().sortedByDescending { (_, d) -> d }.first().second }.toMap()

        for (entry in sortedWordsPerDay) {
            val data = entry.value.map { (a, b) -> a to b }.toMutableList()
            data.add(now to data.last().second)
            val series = chart.addSeries(peopleMap[entry.key]?.first ?: entry.key, data.map { (a, _) -> a }, data.map { (_, b) -> b })
            series.marker = SeriesMarkers.CIRCLE
            series.lineStyle = SeriesLines.SOLID
            peopleMap[entry.key]?.let {
                series.lineColor = it.second
                series.markerColor = it.second
                series.fillColor = it.second
            }
        }
        val historyDay = Calendar.getInstance()
        historyDay.set(2017, Calendar.NOVEMBER, 1, 0, 0, 0)
        var i = 0.0
        var k = 0.0
        var j = 0.0
        val tomorrow = Calendar.getInstance()
        tomorrow.add(Calendar.DAY_OF_MONTH, 2)

        val goalList = mutableMapOf<Date, Double>()
        val myrthesGoalList = mutableMapOf<Date, Double>()
        val jonahsGoalList = mutableMapOf<Date, Double>()

        while (i < 50_000 && k < 80_000 && j < 30_000 && historyDay.getTime().before(tomorrow.getTime())) {
            goalList.put(historyDay.getTime(), i)
            myrthesGoalList.put(historyDay.getTime(), k)
            jonahsGoalList.put(historyDay.getTime(), j)
            j += 1_000
            i += 50_000 / 30
            k += 80_000 / 30
            historyDay.add(Calendar.DAY_OF_MONTH, 1)
        }

        val goalSeries = chart.addSeries("Daily Goal", goalList.keys.toList(), goalList.values.toList())
        goalSeries.marker = SeriesMarkers.SQUARE
        goalSeries.lineStyle = SeriesLines.DASH_DASH
        goalSeries.lineColor = XChartSeriesColors.RED
        goalSeries.markerColor = XChartSeriesColors.RED
        goalSeries.fillColor = XChartSeriesColors.RED

        val myrthesGoalSeries = chart.addSeries("Myrthe's daily Goal", myrthesGoalList.keys.toList(), myrthesGoalList.values.toList())
        myrthesGoalSeries.marker = SeriesMarkers.SQUARE
        myrthesGoalSeries.lineStyle = SeriesLines.DASH_DASH
        myrthesGoalSeries.lineColor = XChartSeriesColors.YELLOW
        myrthesGoalSeries.markerColor = XChartSeriesColors.YELLOW
        myrthesGoalSeries.fillColor = XChartSeriesColors.YELLOW

        val jonahsGoalSeries = chart.addSeries("Jonah's daily Goal", jonahsGoalList.keys.toList(), jonahsGoalList.values.toList())
        jonahsGoalSeries.marker = SeriesMarkers.SQUARE
        jonahsGoalSeries.lineStyle = SeriesLines.DASH_DASH
        jonahsGoalSeries.lineColor = XChartSeriesColors.BROWN
        jonahsGoalSeries.markerColor = XChartSeriesColors.BROWN
        jonahsGoalSeries.fillColor = XChartSeriesColors.BROWN

        BitmapEncoder.saveBitmap(chart, outputFile.absolutePath, BitmapEncoder.BitmapFormat.PNG)
    }

    private fun getChart(file: File, users: List<String>) {
        val stats = users.map { it to state?.get(it) }.mapNotNull { (a, b) -> if (b == null) null else a to b }.toMap()
        generateChart(file, stats)
    }

    private fun addStats(force: Boolean, user: String, stats: MutableMap<String, String>?) {
        if (state != null) {
            if (!state!!.contains(user)) {
                state!!.put(user, HashMap())
                val c = Calendar.getInstance()
                c.set(2017, Calendar.NOVEMBER, 1, 0, 0, 0)
                state!![user]!!.put(c.time, 0)
            }
            val wordsWritten = stats?.get("Total Words Written")?.replace(",", "")?.toInt()
            if (force || !state!![user]?.values?.last()?.equals(wordsWritten)!!)
                wordsWritten?.let { state!![user]!!.put(Date(), it) }
        }
    }
}
