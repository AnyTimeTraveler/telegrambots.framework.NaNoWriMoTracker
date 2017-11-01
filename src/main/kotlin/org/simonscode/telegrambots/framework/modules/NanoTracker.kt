package org.simonscode.telegrambots.framework.modules

import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtilities
import org.jfree.chart.encoders.EncoderUtil
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import org.jsoup.Jsoup
import org.simonscode.telegrambots.framework.Bot
import org.simonscode.telegrambots.framework.Module
import org.simonscode.telegrambots.framework.Utils
import org.telegram.telegrambots.api.methods.send.SendPhoto
import org.telegram.telegrambots.api.objects.Update
import java.awt.BasicStroke
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.regex.Pattern

/**
 * Created by simon on 05.11.16.
 */
class NanoTracker : Module {
    override val name: String
        get() = "NaNoWriMoStatTracker"
    override val version: String
        get() = "1.0"

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
        return null
    }

    override fun setup(state: Any?) {}

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
                val file = File("words.jpg")
                try {
                    getChart(file, users)
                    sender.sendPhoto(SendPhoto().setNewPhoto(file).setCaption("Stats for " + users.joinToString(", ")).setChatId(message.chatId))
                } catch (e: Exception) {
                    Utils.send(sender, message, "Error retrieving data: " + e.localizedMessage)
                }
            }
            "compare" -> Utils.reply(sender, message, compare(users))
            "help" -> Utils.reply(sender, message, getHelpText(null)!!)
            else -> Utils.reply(sender, message, getHelpText(null)!!)
        }
    }

    private fun compare(users: List<String>): String {
        val stats = users.map { getNovelStats(it) }

        val comparableAspects = listOf("Words Written Today", "Target Average Words Per Day", "Words Per Day To Finish On Time", "Total Words Written")

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

    private fun compareAspect(aspect: String, sb: StringBuilder, users: List<String>, stats: List<HashMap<String, String>?>) {
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

    private fun getNovelStats(user: String): HashMap<String, String>? {
        try {
            val doc = Jsoup.parse(URL("http://nanowrimo.org/participants/$user/stats"), 5000)
            val stats = doc.getElementById("novel_stats")
            val output = HashMap<String, String>()
            for (element in stats.children()) {
                output.put(element.child(0).text(), element.child(1).text())
            }
            val wordsPerDay = Pattern.compile(".*var\\s*rawCamperData\\s*=\\s*(\\S+);.*").matcher(doc.toString())
            val wordgoal = Pattern.compile(".*var\\s*parData\\s*=\\s*(\\S+).*").matcher(doc.toString())
            wordsPerDay.find()
            wordgoal.find()
            output.put("chart", wordsPerDay.group(1))
            output.put("wordgoal", wordgoal.group(1))
            return output
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }

    private fun generateChart(outputFile: File, wordsPerDay: Map<String, Map<Int, Int>>) {
        val dataset = XYSeriesCollection()
        for ((key, value) in wordsPerDay) {
            val stats = XYSeries(key)
            for ((key1, value1) in value) {
                stats.add(key1, value1)
            }
            dataset.addSeries(stats)
        }

        val xylineChart = ChartFactory.createXYLineChart(
                "Daily Wordcount",
                "Day",
                "Words",
                dataset,
                PlotOrientation.VERTICAL,
                true, true, false)

        val width = 1024 /* Width of the image */
        val height = 768 /* Height of the image */

        val plot = xylineChart.xyPlot
        val renderer = XYLineAndShapeRenderer()
        renderer.setStroke(BasicStroke(2f))
        renderer.setSeriesPaint(0, Color.BLUE)
        renderer.setSeriesPaint(1, Color.RED)
        renderer.setSeriesPaint(2, Color.MAGENTA)
        renderer.setSeriesPaint(3, Color.CYAN)
        renderer.setSeriesPaint(4, Color.green)
        renderer.setSeriesPaint(5, Color.ORANGE)
        plot.renderer = renderer

        val out = BufferedOutputStream(FileOutputStream(outputFile))
        val image = BufferedImage(width, height, 1)
        val g2 = image.createGraphics()
        plot.render(g2, Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()), 0, null, null)
        g2.dispose()
        EncoderUtil.writeBufferedImage(image, "jpeg", out)
        ChartUtilities.saveChartAsJPEG(outputFile, xylineChart, width, height)
    }

    private fun getChart(file: File, users: List<String>) {
        try {
            var reference: HashMap<Int, Int>? = null
            var once = true
            val data = HashMap<String, HashMap<Int, Int>>()
            for (user in users) {
                // Get stats for user
                val stats = getNovelStats(user)
                if (stats == null) {
                    System.err.println("ERROR 1")
                    continue
                }

                // Convert stats into map of data points
                val days = stats["chart"]?.let { convertChartStringToPointMap(it) }

                if (days == null) {
                    System.err.println("ERROR 2")
                    continue
                }

                if (once) {
                    reference = stats["wordgoal"]?.let { convertChartStringToPointMap(it, days.size) }
                    if (reference != null)
                        once = false
                }
                data.put(user, days)
            }
            if (reference != null) {
                data.put("Daily Goal", reference)
            }

            generateChart(file, data)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun convertChartStringToPointMap(input: String): HashMap<Int, Int> {
        return convertChartStringToPointMap(input, Int.MAX_VALUE)
    }

    private fun convertChartStringToPointMap(input: String, until: Int): HashMap<Int, Int> {
        val days = HashMap<Int, Int>()
        var day = 1
        days.put(0, 0)
        val wordCountPerPerson = input.substring(1, input.length - 1).split(",").dropLastWhile { it.isEmpty() }
        for ((counter, wordcount) in wordCountPerPerson.withIndex()) {
            days.put(day++, Integer.parseInt(wordcount))
            if (counter > until - 2)
                break
        }
        return days
    }
}
