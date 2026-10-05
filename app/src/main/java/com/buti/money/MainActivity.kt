package com.buti.money
import android.content.Context

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ButiTheme { ButiApp() } }
    }
}

class ButiViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ButiDb.get(app).dao()
    val entries = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val savingsGoals = dao.observeSavingsGoals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val prefs = app.getSharedPreferences("buti_settings", Context.MODE_PRIVATE)
    var payday by mutableIntStateOf(prefs.getInt("payday", 15))
        private set
var spendingBudget by mutableStateOf(
    prefs.getFloat("spending_budget", 500f).toDouble()
)
    private set

fun saveSpendingBudget(amount: Double) {
    spendingBudget = amount
    prefs.edit().putFloat("spending_budget", amount.toFloat()).apply()
}
    fun savePayday(day: Int) {
        payday = day
        prefs.edit().putInt("payday", day).apply()
    }
    fun add(
        name: String,
        amount: Double,
        type: String,
        dueDay: Int?,
        recurring: Boolean = false,
        savingsGoalId: Long? = null,
        category: String? = null,
        createdAt: Long? = null
    ) = viewModelScope.launch {
        dao.insert(
            MoneyEntry(
                name=name,
                amount=amount,
                type=type,
                dueDay=dueDay,
                recurring=recurring,
                savingsGoalId=savingsGoalId,
                category=category,
                createdAt=createdAt ?: System.currentTimeMillis()
            )
        )
    }
    fun delete(e: MoneyEntry) = viewModelScope.launch { dao.delete(e) }
    fun update(e: MoneyEntry) = viewModelScope.launch { dao.update(e) }

    fun addSavingsGoal(name: String, targetAmount: Double) = viewModelScope.launch {
        dao.insertSavingsGoal(SavingsGoal(name=name, targetAmount=targetAmount))
    }

    fun deleteSavingsGoal(goal: SavingsGoal) = viewModelScope.launch {
        dao.deleteSavingsGoal(goal)
    }
}

enum class Screen(val label: String) { DASHBOARD("Home"), INCOME("Income"), BILLS("Bills"), SPEND("Spend"), SAVINGS("Savings") }

@Composable fun ButiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ButiApp(vm: ButiViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var quickAddScreen by remember { mutableStateOf<Screen?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("BUTI", fontWeight = FontWeight.Black) }, actions = { Text("Payday ${vm.payday}", modifier=Modifier.padding(end=16.dp)) }) },
        bottomBar = {
            NavigationBar {
                listOf(Screen.DASHBOARD, Screen.INCOME, Screen.BILLS, Screen.SPEND, Screen.SAVINGS).forEach { s ->
                    NavigationBarItem(selected=screen==s, onClick={screen=s}, icon={ Icon(iconFor(s), null) }, label={Text(s.label, fontSize=11.sp, maxLines=1)})
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when(screen) {
                Screen.DASHBOARD -> Dashboard(vm) { target, quickAdd ->
                    quickAddScreen = if (quickAdd) target else null
                    screen = target
                }
                Screen.INCOME -> EntryScreen(vm, "INCOME", "Income", "Add income")
                Screen.BILLS -> EntryScreen(
                    vm, "BILL", "Regular monthly expenses", "Add expense",
                    dueDay=true,
                    openAdd=quickAddScreen == Screen.BILLS
                )
                Screen.SPEND -> EntryScreen(
                    vm, "SPEND", "Everyday spending", "Add spending",
                    openAdd=quickAddScreen == Screen.SPEND
                )
                Screen.SAVINGS -> SavingsScreen(vm)
            }
        }
    }
}

fun iconFor(s: Screen) = when(s) {
    Screen.DASHBOARD -> Icons.Default.Home; Screen.INCOME -> Icons.Default.AddCircle; Screen.BILLS -> Icons.Default.ReceiptLong; Screen.SPEND -> Icons.Default.ShoppingCart; Screen.SAVINGS -> Icons.Default.Savings
}

@Composable fun Dashboard(vm: ButiViewModel, go: (Screen, Boolean)->Unit) {
    val entries by vm.entries.collectAsState()
    val goals by vm.savingsGoals.collectAsState()
    val income = entries.filter{it.type=="INCOME"}.sumOf{it.amount}
    val bills = entries.filter{
        it.type=="BILL" && (!it.recurring || billInCurrentPayCycle(it.dueDay, vm.payday))
    }.sumOf{it.amount}
    val spend = entries
        .filter {
            it.type == "SPEND" &&
            isInCurrentPayCycle(it.createdAt, vm.payday)
        }
        .sumOf { it.amount }
    val saving = entries.filter{it.type=="SAVING"}.sumOf{it.amount}

    val dashboardGoal = goals.firstOrNull()
    val dashboardGoalSaved = dashboardGoal?.let { goal ->
        entries.filter {
            it.type == "SAVING" && it.savingsGoalId == goal.id
        }.sumOf { it.amount }
    } ?: 0.0

    val today = LocalDate.now()
    val nextBill = entries
        .filter { it.type == "BILL" && it.dueDay != null }
        .minByOrNull { bill ->
            val due = bill.dueDay!!
            val thisMonth = YearMonth.from(today)
            val thisDue = thisMonth.atDay(due.coerceAtMost(thisMonth.lengthOfMonth()))
            if (!thisDue.isBefore(today)) {
                java.time.temporal.ChronoUnit.DAYS.between(today, thisDue)
            } else {
                val nextMonth = thisMonth.plusMonths(1)
                val nextDue = nextMonth.atDay(due.coerceAtMost(nextMonth.lengthOfMonth()))
                java.time.temporal.ChronoUnit.DAYS.between(today, nextDue)
            }
        }
    val available = income - bills - spend - saving
    val days = daysUntilPayday(vm.payday).coerceAtLeast(1)
    val daily = available / days
    var showAffordability by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp, vertical=10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Text("Your money. Made simple.", style=MaterialTheme.typography.titleMedium) }
        item { HeroCard("SAFE TO SPEND TODAY", daily, "€${money(available)} available • $days days to payday") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) { MiniCard("Income", income, Modifier.weight(1f)); MiniCard("Bills", bills, Modifier.weight(1f)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) { MiniCard("Spent", spend, Modifier.weight(1f)); MiniCard("Savings", saving, Modifier.weight(1f)) } }

        dashboardGoal?.let { goal ->
            item {
                val progress = if (goal.targetAmount > 0) {
                    (dashboardGoalSaved / goal.targetAmount).coerceIn(0.0, 1.0)
                } else 0.0
                val percent = (progress * 100).toInt()

                Card(
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(18.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp)
                    ) {
                        Text("Savings goal", style=MaterialTheme.typography.bodySmall)
                        Text(goal.name, fontWeight=FontWeight.Bold)
                        Text(
                            "€${money(dashboardGoalSaved)} / €${money(goal.targetAmount)} • $percent%",
                            style=MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress={progress.toFloat()},
                            modifier=Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item { Text("Quick actions", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick={go(Screen.BILLS, true)},
                    modifier=Modifier.weight(1f).height(56.dp),
                    shape=RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add bill")
                }
                OutlinedButton(
                    onClick={go(Screen.SPEND, true)},
                    modifier=Modifier.weight(1f).height(56.dp),
                    shape=RoundedCornerShape(18.dp)
                ) {
                    Text("Add spend")
                }
            }
        }
        item {
            OutlinedButton(
                onClick={showAffordability=true},
                modifier=Modifier.fillMaxWidth().height(48.dp),
                shape=RoundedCornerShape(18.dp)
            ) {
                Text("Can I afford this?")
            }
        }
        nextBill?.let { bill ->
            item {
                Card(
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(18.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=14.dp, vertical=10.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Next bill • ${bill.name}", fontWeight=FontWeight.Bold)
                            Text("Due ${bill.dueDay}", style=MaterialTheme.typography.bodySmall)
                        }
                        Text("€${money(bill.amount)}", fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
        item { PaydayCard(vm) }
        item { SpendingBudgetCard(vm) }
    }

    if (showAffordability) {
        AffordabilityDialog(
            available=available,
            days=days,
            onClose={showAffordability=false}
        )
    }
}

@Composable fun HeroCard(label:String, amount:Double, subtitle:String) = Card(
    shape=RoundedCornerShape(24.dp),
    modifier=Modifier.fillMaxWidth(),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),
    elevation=CardDefaults.cardElevation(defaultElevation=4.dp)
) { Column(Modifier.padding(horizontal=20.dp, vertical=16.dp)) { Text(label, fontWeight=FontWeight.Bold); Text("€${money(amount.coerceAtLeast(0.0))}", style=MaterialTheme.typography.displayMedium, fontWeight=FontWeight.Black); Text(subtitle) } }
@Composable
fun AffordabilityDialog(
    available: Double,
    days: Long,
    onClose: () -> Unit
) {
    var amount by remember { mutableStateOf("") }
    val cost = amount.toDoubleOrNull()
    val remaining = if (cost != null) available - cost else available
    val newDaily = remaining / days.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest=onClose,
        title={Text("Can I afford this?")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value=amount,
                    onValueChange={amount=it.filter { c -> c.isDigit() || c=='.' }},
                    label={Text("Cost (€)")},
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(
                        keyboardType=KeyboardType.Decimal
                    )
                )

                if (cost != null && cost > 0) {
                    Text("Money left: €${money(remaining)}")
                    Text("New daily budget: €${money(newDaily)}")

                    Text(
                        if (remaining >= 0)
                            "This fits within your current available money."
                        else
                            "This is €${money(-remaining)} over your available money.",
                        fontWeight=FontWeight.Bold
                    )
                }
            }
        },
        confirmButton={
            TextButton(onClick=onClose) {
                Text("Done")
            }
        }
    )
}

@Composable fun MiniCard(label:String, amount:Double, modifier:Modifier=Modifier) = Card(
    modifier=modifier,
    shape=RoundedCornerShape(18.dp),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer),
    elevation=CardDefaults.cardElevation(defaultElevation=2.dp)
) { Column(Modifier.padding(horizontal=14.dp, vertical=10.dp)) { Text(label); Text("€${money(amount)}", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) } }

@Composable fun PaydayCard(vm:ButiViewModel) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){ Text("Budget cycle", fontWeight=FontWeight.Bold); Text("Payday-to-payday • payday ${vm.payday}") }; TextButton(onClick={open=true}){Text("Change")} } }
    if(open) PaydayDialog(vm.payday, { vm.savePayday(it); open=false }, {open=false})
}

@Composable fun PaydayDialog(current:Int, save:(Int)->Unit, close:()->Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(onDismissRequest=close, title={Text("Set payday")}, text={ OutlinedTextField(text,{text=it.filter(Char::isDigit).take(2)}, label={Text("Day of month (1–28)")}) }, confirmButton={ Button(onClick={ text.toIntOrNull()?.takeIf{it in 1..28}?.let(save) }, enabled=(text.toIntOrNull()?.let { it in 1..28 } == true)){Text("Save")} }, dismissButton={TextButton(onClick=close){Text("Cancel")}})
}


@Composable
fun SpendingBudgetCard(vm: ButiViewModel) {
    var open by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Spending budget",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "€${money(vm.spendingBudget)} per pay cycle"
                )
            }

            TextButton(onClick = { open = true }) {
                Text("Change")
            }
        }
    }
@Composable
fun SpendingBudgetDialog(
    current: Double,
    save: (Double) -> Unit,
    close: () -> Unit
) {
    var text by remember {
        mutableStateOf(
            if (current % 1.0 == 0.0) current.toInt().toString()
            else current.toString()
        )
    }

    val amount = text.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Set spending budget") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.filter { char ->
                        char.isDigit() || char == '.'
                    }
                },
                label = { Text("Budget per pay cycle (€)") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    amount?.takeIf { it > 0 }?.let(save)
                },
                enabled = amount != null && amount > 0
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = close) {
                Text("Cancel")
            }
        }
    )
}
    if (open) {
        SpendingBudgetDialog(
            current = vm.spendingBudget,
            save = {
                vm.saveSpendingBudget(it)
                open = false
            },
            close = { open = false }
        )
    }
}
@Composable fun SavingsScreen(vm: ButiViewModel) {
    val entries by vm.entries.collectAsState()
    val goals by vm.savingsGoals.collectAsState()
    val savings = entries.filter { it.type == "SAVING" }
    val totalSaved = savings.sumOf { it.amount }
    var showGoalDialog by remember { mutableStateOf(false) }
    var showSavingsDialog by remember { mutableStateOf(false) }
    var editingSaving by remember { mutableStateOf<MoneyEntry?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        Text("Savings", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
        Text("Total saved €${money(totalSaved)}", style=MaterialTheme.typography.titleMedium)

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Text(
                "Savings goals",
                style=MaterialTheme.typography.titleLarge,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.weight(1f)
            )
            Button(onClick={showGoalDialog=true}) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("New goal")
            }
        }

        if (goals.isEmpty()) {
            Text("No savings goals yet.")
        } else {
            goals.forEach { goal ->
                val goalSaved = savings
                    .filter { it.savingsGoalId == goal.id }
                    .sumOf { it.amount }

                val progress = if (goal.targetAmount > 0) {
                    (goalSaved / goal.targetAmount).coerceIn(0.0, 1.0)
                } else 0.0

                val percent = (progress * 100).toInt()

                Card(
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(18.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(goal.name, fontWeight=FontWeight.Bold)
                            Text(
    "€${money(goalSaved)} / €${money(goal.targetAmount)} • $percent%",
    style=MaterialTheme.typography.bodyMedium,
    maxLines=1
)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress={progress.toFloat()},
                                modifier=Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(onClick={vm.deleteSavingsGoal(goal)}) {
                            Icon(Icons.Default.Delete, null)
                        }
                    }
                }
            }
        }

        if (showGoalDialog) {
            SavingsGoalDialog(
                onSave={name,target ->
                    vm.addSavingsGoal(name,target)
                    showGoalDialog=false
                },
                onClose={showGoalDialog=false}
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Text(
                "Savings entries",
                style=MaterialTheme.typography.titleLarge,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.weight(1f)
            )
            Button(onClick={showSavingsDialog=true}) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }

        if (showSavingsDialog) {
            SavingsDepositDialog(
                goals=goals,
                existing=editingSaving,
                onSave={name,amount,goalId ->
                    if (editingSaving == null) {
                        vm.add(
                            name=name,
                            amount=amount,
                            type="SAVING",
                            dueDay=null,
                            savingsGoalId=goalId
                        )
                    } else {
                        vm.update(
                            editingSaving!!.copy(
                                name=name,
                                amount=amount,
                                savingsGoalId=goalId
                            )
                        )
                    }
                    editingSaving=null
                    showSavingsDialog=false
                },
                onClose={
                    editingSaving=null
                    showSavingsDialog=false
                }
            )
        }

        if (savings.isEmpty()) {
            Text("No savings added yet.")
        } else {
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(savings, key={it.id}) { entry ->
                    EntryRow(
    entry,
    {
        editingSaving=entry
        showSavingsDialog=true
    },
    {vm.delete(entry)}
)
                }
            }
        }
    }
}

@Composable
fun SavingsDepositDialog(
    goals: List<SavingsGoal>,
    existing: MoneyEntry? = null,
    onSave: (String, Double, Long?) -> Unit,
    onClose: () -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var amount by remember(existing) { mutableStateOf(existing?.amount?.toString() ?: "") }
    var selectedGoalId by remember(existing) { mutableStateOf(existing?.savingsGoalId) }
    var goalMenuOpen by remember { mutableStateOf(false) }

    val amountValue = amount.toDoubleOrNull()
    val selectedGoal = goals.firstOrNull { it.id == selectedGoalId }
    val valid = name.isNotBlank() && amountValue != null && amountValue > 0

    AlertDialog(
        onDismissRequest=onClose,
        title={Text("Add to savings")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value=name,
                    onValueChange={name=it},
                    label={Text("Name")},
                    singleLine=true
                )

                OutlinedTextField(
                    value=amount,
                    onValueChange={amount=it.filter { c -> c.isDigit() || c=='.' }},
                    label={Text("Amount (€)")},
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(
                        keyboardType=KeyboardType.Decimal
                    )
                )

                Box {
                    OutlinedButton(
                        onClick={goalMenuOpen=true},
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Text(selectedGoal?.name ?: "Choose savings goal")
                    }

                    DropdownMenu(
                        expanded=goalMenuOpen,
                        onDismissRequest={goalMenuOpen=false}
                    ) {
                        DropdownMenuItem(
                            text={Text("No goal")},
                            onClick={
                                selectedGoalId=null
                                goalMenuOpen=false
                            }
                        )

                        goals.forEach { goal ->
                            DropdownMenuItem(
                                text={Text(goal.name)},
                                onClick={
                                    selectedGoalId=goal.id
                                    name=goal.name
                                    goalMenuOpen=false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton={
            Button(
                enabled=valid,
                onClick={onSave(name.trim(), amountValue!!, selectedGoalId)}
            ) {
                Text("Add")
            }
        },
        dismissButton={
            TextButton(onClick=onClose) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SavingsGoalDialog(onSave:(String,Double)->Unit, onClose:()->Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    val targetAmount = target.toDoubleOrNull()
    val valid = name.isNotBlank() && targetAmount != null && targetAmount > 0

    AlertDialog(
        onDismissRequest=onClose,
        title={Text("New savings goal")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value=name,
                    onValueChange={name=it},
                    label={Text("Goal name")},
                    singleLine=true
                )
                OutlinedTextField(
                    value=target,
                    onValueChange={target=it.filter { c -> c.isDigit() || c=='.' }},
                    label={Text("Target amount (€)")},
                    singleLine=true
                )
            }
        },
        confirmButton={
            Button(
                enabled=valid,
                onClick={onSave(name.trim(), targetAmount!!)}
            ) { Text("Create goal") }
        },
        dismissButton={
            TextButton(onClick=onClose) { Text("Cancel") }
        }
    )
}

@Composable fun EntryScreen(
    vm:ButiViewModel,
    type:String,
    title:String,
    addLabel:String,
    dueDay:Boolean=false,
    openAdd:Boolean=false
) {
    val all by vm.entries.collectAsState()
    val list = all.filter { it.type == type }.let { entries ->
        if (type == "BILL") entries.sortedBy { it.dueDay ?: 32 } else entries
    }

    val currentCycleList = if (type == "SPEND") {
        list.filter { isInCurrentPayCycle(it.createdAt, vm.payday) }
    } else {
        list
    }

    val previousSpendList = if (type == "SPEND") {
        list.filterNot { isInCurrentPayCycle(it.createdAt, vm.payday) }
    } else {
        emptyList()
    }

    val previousCycleSpend = if (type == "SPEND") {
        list.filter { isInPreviousPayCycle(it.createdAt, vm.payday) }
            .sumOf { it.amount }
    } else {
        0.0
    }

    val currentCycleSpend = if (type == "SPEND") {
        currentCycleList.sumOf { it.amount }
    } else {
        0.0
    }

    val cycleDifference = currentCycleSpend - previousCycleSpend

    val biggestSpend = if (type == "SPEND") {
        currentCycleList.maxByOrNull { it.amount }
    } else {
        null
    }

    val categoryTotals = if (type == "SPEND") {
        currentCycleList.groupBy { it.category ?: "Other" }
            .mapValues { (_, entries) -> entries.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    } else {
        emptyList()
    }

    val previousCategoryTotals = if (type == "SPEND") {
        list.filter { isInPreviousPayCycle(it.createdAt, vm.payday) }
            .groupBy { it.category ?: "Other" }
            .mapValues { (_, entries) -> entries.sumOf { it.amount } }
    } else {
        emptyMap()
    }

    val biggestCategoryIncrease = if (type == "SPEND") {
        categoryTotals
            .map { (category, currentTotal) ->
                val previousTotal = previousCategoryTotals[category] ?: 0.0
                category to (currentTotal - previousTotal)
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
    } else {
        null
    }

    var show by remember { mutableStateOf(openAdd) }; var edit by remember { mutableStateOf<MoneyEntry?>(null) }
Column(
    Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
        .padding(bottom = 90.dp)
) {
      Text(
    title,
    style = MaterialTheme.typography.headlineSmall,
    fontWeight = FontWeight.Bold
)

Text(
    text = if (type == "SPEND") {
        "This pay cycle €${money(currentCycleList.sumOf { it.amount })}"
    } else {
        "Total €${money(list.sumOf { it.amount })}"
    },
    style = MaterialTheme.typography.titleMedium
)

if (type == "SPEND") {
    Spacer(Modifier.height(8.dp))

    val budgetRemaining = vm.spendingBudget - currentCycleSpend
val daysLeft = daysUntilPayday(vm.payday).coerceAtLeast(1)

val dailyBudgetRemaining = if (budgetRemaining > 0) {
    budgetRemaining / daysLeft
} else {
    0.0
}
    val budgetProgress = if (vm.spendingBudget > 0) {
        (currentCycleSpend / vm.spendingBudget)
            .toFloat()
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    Text(
        "Spending budget",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold
    )

    Text(
        "€${money(currentCycleSpend)} spent of €${money(vm.spendingBudget)}",
        style = MaterialTheme.typography.bodySmall
    )
Text(
    "€${money(dailyBudgetRemaining)} per day until payday",
    style = MaterialTheme.typography.bodySmall,
    fontWeight = FontWeight.Medium
)
    Text(Text(
    text = when {
        budgetProgress < 0.50f -> "✓ On track"
        budgetProgress < 0.80f -> "Keep an eye on your spending"
        budgetProgress < 1.00f -> "⚠ Close to your budget"
        else -> "⚠ Budget reached"
    },
    style = MaterialTheme.typography.bodySmall,
    fontWeight = FontWeight.Medium
)
        if (budgetRemaining >= 0) {
            "€${money(budgetRemaining)} left"
        } else {
            "€${money(-budgetRemaining)} over budget"
        },
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold
    )

    LinearProgressIndicator(
        progress = { budgetProgress },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
    )
}

        if (type == "SPEND" && previousCycleSpend > 0) {
            Spacer(Modifier.height(6.dp))

            val comparisonText = when {
                cycleDifference > 0 ->
                    "€${money(cycleDifference)} more than last pay cycle"
                cycleDifference < 0 ->
                    "€${money(-cycleDifference)} less than last pay cycle"
                else ->
                    "Same spending as last pay cycle"
            }

            Text(
    "Last pay cycle: €${money(previousCycleSpend)}",
    style = MaterialTheme.typography.bodySmall
)

            Text(
                comparisonText,
                style=MaterialTheme.typography.bodySmall,
                fontWeight=FontWeight.Medium
            )

       biggestCategoryIncrease?.let { (category, increase) ->
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                "Spending insight",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                "$category is up €${money(increase)} from last pay cycle",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
        }

if (type == "SPEND" && biggestSpend != null) {
    Spacer(Modifier.height(6.dp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "Biggest spend",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                biggestSpend.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            Text(
                "€${money(biggestSpend.amount)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

    if (type == "SPEND" && categoryTotals.isNotEmpty()) {
    Spacer(Modifier.height(10.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Where your money goes",
                fontWeight = FontWeight.Bold
            )

            categoryTotals.forEach { (category, total) ->

                val proportion = if (currentCycleSpend > 0) {
                    (total / currentCycleSpend)
                        .toFloat()
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
    "$category · ${(proportion * 100).toInt()}%",
    modifier = Modifier.weight(1f)
)

                        Text(
                            "€${money(total)}",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LinearProgressIndicator(
                        progress = { proportion },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                }
            }
        }
    }
}
       Spacer(Modifier.height(12.dp))

Button(
    onClick = {
        edit = null
        show = true
    },
    modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
) {
    Icon(Icons.Default.Add, null)
    Spacer(Modifier.width(8.dp))
    Text(addLabel)
}

Spacer(Modifier.height(8.dp))

if (list.isEmpty()) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center
    ) {
        Text("Nothing here yet. Tap Add to start.")
    }
} else {
    Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
list.forEach { e ->
            EntryRow(
                e,
                {
                    edit = e
                    show = true
                },
                {
                    vm.delete(e)
                }
            )
        }
    }
}
    }
    if(show) EntryDialog(
        type,
        dueDay,
        edit,
        onSave={name,amount,day,recurring,category,transactionDate ->
            if(edit==null) {
                vm.add(
                    name=name,
                    amount=amount,
                    type=type,
                    dueDay=day,
                    recurring=recurring,
                    category=category,
                    createdAt=transactionDate
                )
            } else {
                vm.update(
                    edit!!.copy(
                        name=name,
                        amount=amount,
                        dueDay=day,
                        recurring=recurring,
                        category=category,
                        createdAt=transactionDate ?: edit!!.createdAt
                    )
                )
            }
            show=false
        },
        onClose={show=false}
    )
}

@Composable fun EntryRow(e:MoneyEntry, edit:()->Unit, delete:()->Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal=14.dp, vertical=10.dp).fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Text(
                    e.name,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text("€${money(e.amount)}", fontWeight=FontWeight.Bold)
            }

            e.dueDay?.let {
                Text("Due day $it", style=MaterialTheme.typography.bodySmall)
            }
            if (e.type == "SPEND") {
                e.category?.let {
                    Text(it, style=MaterialTheme.typography.bodySmall)
                }

                val entryDate = java.time.Instant
                    .ofEpochMilli(e.createdAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()

                val dateText = entryDate.format(
                    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                )

                Text(
                    dateText,
                    style=MaterialTheme.typography.bodySmall
                )
            }
            if(e.recurring) {
                Text("↻ Monthly", style=MaterialTheme.typography.bodySmall)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.End
            ) {
                IconButton(onClick=edit) {
                    Icon(Icons.Default.Edit, contentDescription="Edit")
                }
                IconButton(onClick={confirmDelete=true}) {
                    Icon(Icons.Default.Delete, contentDescription="Delete")
                }
            }
        }
    }

    if(confirmDelete) {
        AlertDialog(
            onDismissRequest={confirmDelete=false},
            title={Text("Delete ${e.name}?")},
            text={Text("This entry will be permanently removed.")},
            confirmButton={
                Button(onClick={
                    confirmDelete=false
                    delete()
                }) { Text("Delete") }
            },
            dismissButton={
                TextButton(onClick={confirmDelete=false}) { Text("Cancel") }
            }
        )
    }
}

@Composable fun EntryDialog(type:String, askDueDay:Boolean, existing:MoneyEntry?, onSave:(String,Double,Int?,Boolean,String?,Long?)->Unit, onClose:()->Unit) {
    var name by remember(existing){mutableStateOf(existing?.name ?: "")}; var amount by remember(existing){mutableStateOf(existing?.amount?.toString() ?: "")}; var day by remember(existing){mutableStateOf(existing?.dueDay?.toString() ?: "")}
    var recurring by remember(existing){mutableStateOf(existing?.recurring ?: false)}
    var category by remember(existing){mutableStateOf(existing?.category ?: "Other")}
    var categoryMenuOpen by remember { mutableStateOf(false) }

    var spendDate by remember(existing) {
        mutableStateOf(
            existing?.let {
                java.time.Instant.ofEpochMilli(it.createdAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
            } ?: LocalDate.now().toString()
        )
    }

  val categories = listOf("Groceries", "Fuel", "Eating out", "Shopping", "Home & DIY", "Transport", "Entertainment", "Other")
    val spendDateValid = if (type == "SPEND") {
        try {
            LocalDate.parse(spendDate)
            true
        } catch (_: Exception) {
            false
        }
    } else {
        true
    }

    val valid = name.isNotBlank() &&
        (amount.toDoubleOrNull() ?: 0.0) > 0 &&
        (!askDueDay || day.isBlank() || (day.toIntOrNull()?.let { it in 1..31 } == true)) &&
        spendDateValid
    AlertDialog(onDismissRequest=onClose, title={Text(if(existing==null) "Add ${type.lowercase()}" else "Edit item")}, text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)){ OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true); OutlinedTextField(
    value=amount,
    onValueChange={amount=it.filter{c->c.isDigit()||c=='.'}},
    label={Text("Amount (€)")},
    singleLine=true,
    keyboardOptions=KeyboardOptions(
        keyboardType=KeyboardType.Decimal
    )
)

        if (type == "SPEND") {
            Box {
                OutlinedButton(
                    onClick={categoryMenuOpen=true},
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Text("Category: $category")
                }

                DropdownMenu(
                    expanded=categoryMenuOpen,
                    onDismissRequest={categoryMenuOpen=false}
                ) {
                    categories.forEach { option ->
                        DropdownMenuItem(
                            text={Text(option)},
                            onClick={
                                category=option
                                categoryMenuOpen=false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value=spendDate,
                onValueChange={spendDate=it.take(10)},
                label={Text("Date (YYYY-MM-DD)")},
                singleLine=true,
                modifier=Modifier.fillMaxWidth()
            )
        }

        if(askDueDay) {
            OutlinedTextField(day,{day=it.filter(Char::isDigit).take(2)},label={Text("Due day (optional)")},singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(
                    checked = recurring,
                    onCheckedChange = { recurring = it }
                )
                Text("Repeat every month")
            }
        } } }, confirmButton={Button(enabled=valid,onClick={onSave(
    name.trim(),
    amount.toDouble(),
    day.toIntOrNull(),
    recurring,
    if (type == "SPEND") category else null,
    if (type == "SPEND") {
        LocalDate.parse(spendDate)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } else null
)}){Text("Save")}}, dismissButton={TextButton(onClick=onClose){Text("Cancel")}})
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

fun isInCurrentPayCycle(createdAt: Long, payday: Int): Boolean {
    val today = LocalDate.now()
    val nextPayday = today.plusDays(daysUntilPayday(payday))
    val cycleStart = nextPayday.minusMonths(1)

    val entryDate = java.time.Instant
        .ofEpochMilli(createdAt)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()

    return !entryDate.isBefore(cycleStart) && entryDate.isBefore(nextPayday)
}

fun isInPreviousPayCycle(createdAt: Long, payday: Int): Boolean {
    val today = LocalDate.now()
    val nextPayday = today.plusDays(daysUntilPayday(payday))

    val currentCycleStart = nextPayday.minusMonths(1)
    val previousCycleStart = currentCycleStart.minusMonths(1)

    val entryDate = java.time.Instant
        .ofEpochMilli(createdAt)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()

    return !entryDate.isBefore(previousCycleStart) &&
           entryDate.isBefore(currentCycleStart)
}


