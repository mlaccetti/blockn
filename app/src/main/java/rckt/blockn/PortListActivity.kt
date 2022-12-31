package rckt.blockn

import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.*
import rckt.blockn.databinding.PortsListBinding
import java.util.*

val DEFAULT_PORTS = setOf(
  80, // HTTP
  443, // HTTPS
  8000, 8080, 8888, 9000 // Common local dev ports
)

const val MIN_PORT = 1
const val MAX_PORT = 65535

// Used to both to send and return the current list of selected ports
const val SELECTED_PORTS_EXTRA = "tech.httptoolkit.android.SELECTED_PORTS_EXTRA"

class PortListActivity : AppCompatActivity(), CoroutineScope by MainScope() {
  private lateinit var binding: PortsListBinding
  private lateinit var ports: TreeSet<Int> // TreeSet = Mutable + Sorted

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = PortsListBinding.inflate(layoutInflater)
    val view = binding.root
    setContentView(view)

    ports = intent.getIntArrayExtra(SELECTED_PORTS_EXTRA)!!
      .toCollection(TreeSet())


    binding.portsListRecyclerView.adapter =
      PortListAdapter(
        ports,
        ::deletePort
      )

    binding.portsListInput.filters = arrayOf(MinMaxInputFilter(MIN_PORT, MAX_PORT))

    // Match the UI enabled state to the input field contents:

    binding.portsListAddButton.isEnabled = false
    binding.portsListInput.doAfterTextChanged {
      binding.portsListAddButton.isEnabled = isValidInput(it.toString())
    }

    // Add ports when enter/+ is pressed/clicked:
    binding.portsListAddButton.setOnClickListener { addEnteredPort() }
    binding.portsListInput.setOnEditorActionListener { _, _, _ ->
      addEnteredPort()
      return@setOnEditorActionListener true
    }

    // Show the menu, and listen for clicks:
    binding.portsListMoreMenu.setOnClickListener {
      PopupMenu(this, binding.portsListMoreMenu).apply {
        this.inflate(R.menu.menu_ports_list)

        this.menu.findItem(R.id.action_reset_ports).isEnabled =
          ports != DEFAULT_PORTS

        this.setOnMenuItemClickListener {
          when (it.itemId) {
            R.id.action_reset_ports -> {
              ports.clear()
              ports.addAll(DEFAULT_PORTS)
              updateList()
              true
            }
            else -> false
          }
        }
      }.show()
    }
  }

  private fun isValidInput(input: String): Boolean =
    input.toIntOrNull() != null &&
      !ports.contains(input.toInt())

  private fun addEnteredPort() {
    if (!isValidInput(binding.portsListInput.text.toString())) return

    ports.add(binding.portsListInput.text.toString().toInt())
    binding.portsListInput.text.clear()
    updateList()
  }

  private fun deletePort(port: Int) {
    ports.remove(port)
    updateList()
  }

  private fun updateList() {
    binding.portsListRecyclerView.adapter?.notifyDataSetChanged()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    setResult(
      RESULT_OK, Intent().putExtra(
        SELECTED_PORTS_EXTRA,
        ports.toIntArray()
      )
    )
    finish()
  }
}
