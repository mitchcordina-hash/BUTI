package com.buti.money
\nimport android.content.Context

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ButiTheme { ButiApp() } }
    }
}

class ButiViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ButiDb.get(app).dao()
    val entries = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val prefs = app.getSharedPreferences("buti_settings", Context.MODE_PRIVATE)
    var payday by mutableIntStateOf(prefs.getInt("payday", 15))
        private set

    fun setPayday(day: Int) {
        payday = day
        prefs.edit().putInt("payday", day).apply()
    }
    fun add(name: String, amount: Double, type: String, dueDay: Int?, recurring: Boolean = false) = viewModelScope.launch { dao.insert(MoneyEntry(name=name, amount=amount, type=type, dueDay=dueDay, recurring=recurring)) }
    fun delete(e: MoneyEntry) = viewModelScope.launch { dao.delete(e) }
    fun update(e: MoneyEntry) = viewModelScope.launch { dao.update(e) }
}

enum class Screen(val label: String) { DASHBOARD("Home"), INCOME("Income"), BILLS("Bills"), SPEND("Spend"), SAVINGS("Save") }

@Composable fun ButiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ButiApp(vm: ButiViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("BUTI", fontWeight = FontWeight.Black) }, actions = { Text("Payday ${vm.payday}", modifier=Modifier.padding(end=16.dp)) }) },
        bottomBar = {
            NavigationBar {
                listOf(Screen.DASHBOARD, Screen.INCOME, Screen.BILLS, Screen.SPEND, Screen.SAVINGS).forEach { s ->
                    NavigationBarItem(selected=screen==s, onClick={screen=s}, icon={ Icon(iconFor(s), null) }, label={Text(s.label)})
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when(screen) {
                Screen.DASHBOARD -> Dashboard(vm) { screen = it }
                Screen.INCOME -> EntryScreen(vm, "INCOME", "Income", "Add income")
                Screen.BILLS -> EntryScreen(vm, "BILL", "Regular monthly expenses", "Add expense", true)
                Screen.SPEND -> EntryScreen(vm, "SPEND", "Everyday spending", "Add spending")
                Screen.SAVINGS -> EntryScreen(vm, "SAVING", "Savings", "Add savings")
            }
        }
    }
}

fun iconFor(s: Screen) = when(s) {
    Screen.DASHBOARD -> Icons.Default.Home; Screen.INCOME -> Icons.Default.AddCircle; Screen.BILLS -> Icons.Default.ReceiptLong; Screen.SPEND -> Icons.Default.ShoppingCart; Screen.SAVINGS -> Icons.Default.Savings
}

@Composable fun Dashboard(vm: ButiViewModel, go: (Screen)->Unit) {
    val entries by vm.entries.collectAsState()
    val income = entries.filter{it.type=="INCOME"}.sumOf{it.amount}
    val bills = entries.filter{
        it.type=="BILL" && (!it.recurring || billInCurrentPayCycle(it.dueDay, vm.payday))
    }.sumOf{it.amount}
    val spend = entries.filter{it.type=="SPEND"}.sumOf{it.amount}
    val saving = entries.filter{it.type=="SAVING"}.sumOf{it.amount}
    val available = income - bills - spend - saving
    val days = daysUntilPayday(vm.payday).coerceAtLeast(1)
    val daily = available / days
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Your money. Made simple.", style=MaterialTheme.typography.titleMedium) }
        item { HeroCard("SAFE TO SPEND TODAY", daily, "€${money(available)} available • $days days to payday") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) { MiniCard("Income", income, Modifier.weight(1f)); MiniCard("Bills", bills, Modifier.weight(1f)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) { MiniCard("Spent", spend, Modifier.weight(1f)); MiniCard("Savings", saving, Modifier.weight(1f)) } }
        item { Text("Quick actions", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
        item { Button(onClick={go(Screen.BILLS)}, modifier=Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add regular expense") } }
        item { OutlinedButton(onClick={go(Screen.SPEND)}, modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("Record spending") } }
        item { PaydayCard(vm) }
    }
}

@Composable fun HeroCard(label:String, amount:Double, subtitle:String) = Card(shape=RoundedCornerShape(24.dp), modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp)) { Text(label, fontWeight=FontWeight.Bold); Text("€${money(amount.coerceAtLeast(0.0))}", style=MaterialTheme.typography.displayMedium, fontWeight=FontWeight.Black); Text(subtitle) } }
@Composable fun MiniCard(label:String, amount:Double, modifier:Modifier=Modifier) = Card(modifier=modifier) { Column(Modifier.padding(16.dp)) { Text(label); Text("€${money(amount)}", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) } }

@Composable fun PaydayCard(vm:ButiViewModel) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){ Text("Budget cycle", fontWeight=FontWeight.Bold); Text("Payday-to-payday • payday ${vm.payday}") }; TextButton(onClick={open=true}){Text("Change")} } }
    if(open) PaydayDialog(vm.payday, { vm.setPayday(it); open=false }, {open=false})
}

@Composable fun PaydayDialog(current:Int, save:(Int)->Unit, close:()->Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(onDismissRequest=close, title={Text("Set payday")}, text={ OutlinedTextField(text,{text=it.filter(Char::isDigit).take(2)}, label={Text("Day of month (1–28)")}) }, confirmButton={ Button(onClick={ text.toIntOrNull()?.takeIf{it in 1..28}?.let(save) }, enabled=(text.toIntOrNull()?.let { it in 1..28 } == true)){Text("Save")} }, dismissButton={TextButton(onClick=close){Text("Cancel")}})
}

@Composable fun EntryScreen(vm:ButiViewModel, type:String, title:String, addLabel:String, dueDay:Boolean=false) {
    val all by vm.entries.collectAsState(); val list = all.filter{it.type==type}
    var show by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<MoneyEntry?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
        Text("Total €${money(list.sumOf{it.amount})}", style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick={show=true; edit=null}, modifier=Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Default.Add,null); Spacer(Modifier.width(8.dp)); Text(addLabel) }
        Spacer(Modifier.height(8.dp))
        if(list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center){Text("Nothing here yet. Tap Add to start.")}
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { items(list, key={it.id}) { e -> EntryRow(e, {edit=e; show=true}, {vm.delete(e)}) } }
    }
    if(show) EntryDialog(type, dueDay, edit, onSave={name,amount,day,recurring -> if(edit==null) vm.add(name,amount,type,day,recurring) else vm.update(edit!!.copy(name=name, amount=amount, dueDay=day, recurring=recurring)); show=false }, onClose={show=false})
}

@Composable fun EntryRow(e:MoneyEntry, edit:()->Unit, delete:()->Unit) = Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){Text(e.name,fontWeight=FontWeight.Bold); e.dueDay?.let{Text("Due day $it", style=MaterialTheme.typography.bodySmall)}
            if(e.recurring) Text("↻ Monthly", style=MaterialTheme.typography.bodySmall) }; Text("€${money(e.amount)}",fontWeight=FontWeight.Bold); IconButton(onClick=edit){Icon(Icons.Default.Edit,null)}; IconButton(onClick=delete){Icon(Icons.Default.Delete,null)} } }

@Composable fun EntryDialog(type:String, askDueDay:Boolean, existing:MoneyEntry?, onSave:(String,Double,Int?,Boolean)->Unit, onClose:()->Unit) {
    var name by remember(existing){mutableStateOf(existing?.name ?: "")}; var amount by remember(existing){mutableStateOf(existing?.amount?.toString() ?: "")}; var day by remember(existing){mutableStateOf(existing?.dueDay?.toString() ?: "")}
    var recurring by remember(existing){mutableStateOf(existing?.recurring ?: false)}
    val valid = name.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0 && (!askDueDay || day.isBlank() || (day.toIntOrNull()?.let { it in 1..31 } == true))
    AlertDialog(onDismissRequest=onClose, title={Text(if(existing==null) "Add ${type.lowercase()}" else "Edit item")}, text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)){ OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true); OutlinedTextField(amount,{amount=it.filter{c->c.isDigit()||c=='.'}},label={Text("Amount (€)")},singleLine=true); if(askDueDay) {
            OutlinedTextField(day,{day=it.filter(Char::isDigit).take(2)},label={Text("Due day (optional)")},singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(
                    checked = recurring,
                    onCheckedChange = { recurring = it }
                )
                Text("Repeat every month")
            }
        } } }, confirmButton={Button(enabled=valid,onClick={onSave(name.trim(),amount.toDouble(),day.toIntOrNull(),recurring)}){Text("Save")}}, dismissButton={TextButton(onClick=onClose){Text("Cancel")}})
}

fun money(v:Double)=String.format(Locale.US,"%.2f",v)
fun billInCurrentPayCycle(dueDay: Int?, payday: Int): Boolean {
    if (dueDay == null) return false
    val today = LocalDate.now()
    val daysToPayday = daysUntilPayday(payday)
    val nextPayday = today.plusDays(daysToPayday)
    val cycleStart = nextPayday.minusMonths(1)
    val thisMonthDue = YearMonth.from(today).atDay(
        dueDay.coerceAtMost(YearMonth.from(today).lengthOfMonth())
    )
    val nextMonth = YearMonth.from(today).plusMonths(1)
    val nextMonthDue = nextMonth.atDay(
        dueDay.coerceAtMost(nextMonth.lengthOfMonth())
    )
    return (thisMonthDue >= cycleStart && thisMonthDue < nextPayday) ||
           (nextMonthDue >= cycleStart && nextMonthDue < nextPayday)
}

fun daysUntilPayday(payday:Int):Long { val today=LocalDate.now(); var next=YearMonth.from(today).atDay(payday.coerceAtMost(YearMonth.from(today).lengthOfMonth())); if(!next.isAfter(today)) { val ym=YearMonth.from(today).plusMonths(1); next=ym.atDay(payday.coerceAtMost(ym.lengthOfMonth())) }; return ChronoUnit.DAYS.between(today,next) }
