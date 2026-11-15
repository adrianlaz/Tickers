package com.tickers.app.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tickers.app.portfolio.AssetSummary
import com.tickers.app.portfolio.PortfolioCalculator
import com.tickers.app.portfolio.PortfolioCalculator.parseDecimal
import com.tickers.app.ui.Format
import com.tickers.app.ui.common.SectionTitle
import com.tickers.app.ui.common.StatGrid
import com.tickers.app.ui.theme.changeColor

/** "What if I sold now / at price X?" for one asset's open lots (FIFO). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulateSaleSheet(asset: AssetSummary, onDismiss: () -> Unit) {
    var priceText by rememberSaveable { mutableStateOf(asset.price?.let(Format::plain) ?: "") }
    var qtyText by rememberSaveable { mutableStateOf(Format.plain(asset.openQuantity)) }
    var feeText by rememberSaveable { mutableStateOf("0") }

    val price = parseDecimal(priceText)
    val qty = parseDecimal(qtyText)
    val feePct = parseDecimal(feeText) ?: 0.0
    val qtyError = qty == null || qty <= 0 || qty > asset.openQuantity + 1e-9

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Simulate selling ${Format.ticker(asset.symbol)}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Takes your oldest positions first (FIFO). Nothing is saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    priceText, { priceText = it }, label = { Text("Sell price ($)") },
                    isError = price == null, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    feeText, { feeText = it }, label = { Text("Fee %") },
                    singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(0.6f),
                )
            }
            OutlinedTextField(
                qtyText, { qtyText = it },
                label = { Text("Quantity (max ${Format.plain(asset.openQuantity)})") },
                isError = qtyError, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
            )

            if (price != null && !qtyError) {
                val sim = PortfolioCalculator.simulateSale(asset.openLots, price, qty, feePct)
                StatGrid(
                    listOf(
                        Triple("You receive", Format.usd(sim.proceeds), Color.Unspecified),
                        Triple("Cost of these lots", Format.usd(sim.cost), Color.Unspecified),
                        Triple("Profit", Format.signedUsd(sim.pnl), changeColor(sim.pnl)),
                        Triple("Return", Format.signedPct(sim.pnlPct), changeColor(sim.pnl)),
                    )
                )
                SectionTitle("Per position")
                sim.lots.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "${Format.qty(s.quantity, asset.symbol)} bought @ ${Format.price(s.lot.buy.price)}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(Format.signedUsd(s.pnl), color = changeColor(s.pnl), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }

            SectionTitle("Sell price needed (whole position)")
            listOf(0.0, 5.0, 10.0, 25.0, 50.0, 100.0).forEach { pct ->
                val target = PortfolioCalculator.priceForReturn(asset.openLots, pct, feePct)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(if (pct == 0.0) "Break-even" else "+${pct.toInt()}%", modifier = Modifier.weight(1f))
                    Text(Format.price(target))
                }
            }
            Text(" ", modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}
