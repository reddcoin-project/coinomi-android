package com.reddcoin.wallet.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.FilterQueryProvider;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.reddcoin.core.coins.CoinType;
import com.reddcoin.core.coins.FiatType;
import com.reddcoin.core.coins.Value;
import com.reddcoin.core.uri.CoinURI;
import com.reddcoin.core.uri.CoinURIParseException;
import com.reddcoin.core.util.GenericUtils;
import com.reddcoin.core.wallet.WalletAccount;
import com.reddcoin.core.wallet.WalletPocketHD;
import com.reddcoin.core.wallet.exceptions.NoSuchPocketException;
import com.reddcoin.wallet.AddressBookProvider;
import com.reddcoin.wallet.Configuration;
import com.reddcoin.wallet.Constants;
import com.reddcoin.wallet.ExchangeRatesProvider;
import com.reddcoin.wallet.ExchangeRatesProvider.ExchangeRate;
import com.reddcoin.wallet.R;
import com.reddcoin.wallet.WalletApplication;
import com.reddcoin.wallet.ui.widget.AmountEditView;
import com.reddcoin.wallet.util.ThrottlingWalletChangeListener;
import com.reddcoin.wallet.util.WeakHandler;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import javax.annotation.Nullable;

import static com.reddcoin.core.Preconditions.checkNotNull;

/**
 * Fragment that prepares a transaction
 *
 * @author Andreas Schildbach
 * @author John L. Jegutanis
 */
public class SendFragment extends Fragment {
    private static final Logger log = LoggerFactory.getLogger(SendFragment.class);

    // the fragment initialization parameters
    private static final int REQUEST_CODE_SCAN = 0;
    private static final int SIGN_TRANSACTION = 1;

    private static final int UPDATE_EXCHANGE_RATE = 0;
    private static final int UPDATE_WALLET_CHANGE = 1;

    // Loader IDs
    private static final int ID_RATE_LOADER = 0;
    private static final int ID_RECEIVING_ADDRESS_LOADER = 1;

    private CoinType type;
    @Nullable private Coin lastBalance; // TODO setup wallet watcher for the latest balance
    private AutoCompleteTextView sendToAddressView;
    private TextView addressError;
    private CurrencyCalculatorLink amountCalculatorLink;
    private TextView amountError;
    private TextView amountWarning;
    private ImageButton scanQrCodeButton;
    private Button sendConfirmButton;

    private State state = State.INPUT;
    private Address address;
    private Coin sendAmount;
    private WalletApplication application;
    private Listener listener;
    @Nullable private WalletPocketHD pocket;
    private Configuration config;
    private NavigationDrawerFragment mNavigationDrawerFragment;
    private LoaderManager loaderManager;
    private ReceivingAddressViewAdapter sendToAddressViewAdapter;

    Handler handler = new MyHandler(this);

    private static class MyHandler extends WeakHandler<SendFragment> {
        public MyHandler(SendFragment referencingObject) { super(referencingObject); }

        @Override
        protected void weakHandleMessage(SendFragment ref, Message msg) {
            switch (msg.what) {
                case UPDATE_EXCHANGE_RATE:
                    ref.onExchangeRateUpdate((com.reddcoin.core.util.ExchangeRate) msg.obj);
                    break;
                case UPDATE_WALLET_CHANGE:
                    ref.onWalletUpdate();
            }
        }
    }


    private enum State {
        INPUT, PREPARATION, SENDING, SENT, FAILED
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param accountId the id of an account
     * @return A new instance of fragment WalletSendCoins.
     */
    public static SendFragment newInstance(String accountId) {
        SendFragment fragment = new SendFragment();
        Bundle args = new Bundle();
        args.putSerializable(Constants.ARG_ACCOUNT_ID, accountId);
        fragment.setArguments(args);
        return fragment;
    }

    public SendFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            String accountId = checkNotNull(getArguments().getString(Constants.ARG_ACCOUNT_ID));
            //TODO
            pocket = (WalletPocketHD) application.getAccount(accountId);
        }

        if (pocket == null) {
            Toast.makeText(getActivity(), R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
            return;
        }

        type = pocket.getCoinType();
        updateBalance();
        setHasOptionsMenu(true);
        mNavigationDrawerFragment = (NavigationDrawerFragment)
                getFragmentManager().findFragmentById(R.id.navigation_drawer);

        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
        loaderManager.initLoader(ID_RECEIVING_ADDRESS_LOADER, null, receivingAddressLoaderCallbacks);
    }

    private void updateBalance() {
        if (pocket != null) {
            lastBalance = pocket.getBalance(false).toCoin();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_send, container, false);

        sendToAddressView = (AutoCompleteTextView) view.findViewById(R.id.send_to_address);
        sendToAddressViewAdapter = new ReceivingAddressViewAdapter(application);
        sendToAddressView.setAdapter(sendToAddressViewAdapter);
        sendToAddressView.setOnFocusChangeListener(receivingAddressListener);
        sendToAddressView.addTextChangedListener(receivingAddressListener);

        AmountEditView sendCoinAmountView = (AmountEditView) view.findViewById(R.id.send_coin_amount);
        sendCoinAmountView.setType(type);
        sendCoinAmountView.setFormat(type.getMonetaryFormat());

        AmountEditView sendLocalAmountView = (AmountEditView) view.findViewById(R.id.send_local_amount);
        sendLocalAmountView.setFormat(FiatType.FRIENDLY_FORMAT);

        amountCalculatorLink = new CurrencyCalculatorLink(sendCoinAmountView, sendLocalAmountView);
        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());

        addressError = (TextView) view.findViewById(R.id.address_error_message);
        addressError.setVisibility(View.GONE);
        amountError = (TextView) view.findViewById(R.id.amount_error_message);
        amountError.setVisibility(View.GONE);
        amountWarning = (TextView) view.findViewById(R.id.amount_warning_message);
        amountWarning.setVisibility(View.GONE);

        scanQrCodeButton = (ImageButton) view.findViewById(R.id.scan_qr_code);
        scanQrCodeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleScan();
            }
        });

        sendConfirmButton = (Button) view.findViewById(R.id.send_confirm);
        sendConfirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                validateAddress();
                validateAmount();
                if (everythingValid())
                    handleSendConfirm();
                else
                    requestFocusFirst();
            }
        });

        return view;
    }

    @Override
    public void onDestroyView() {
        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        loaderManager.destroyLoader(ID_RECEIVING_ADDRESS_LOADER);
        loaderManager.destroyLoader(ID_RATE_LOADER);

        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();

        amountCalculatorLink.setListener(amountsListener);

        if (pocket != null)
            pocket.addEventListener(transactionChangeListener, Threading.SAME_THREAD);
        updateBalance();

        updateView();
    }

    @Override
    public void onPause() {
        if (pocket != null) pocket.removeEventListener(transactionChangeListener);
        transactionChangeListener.removeCallbacks();

        amountCalculatorLink.setListener(null);

        super.onPause();
    }

    private void handleScan() {
        startActivityForResult(new Intent(getActivity(), ScanActivity.class), REQUEST_CODE_SCAN);
    }

    private void handleSendConfirm() {
        if (!everythingValid()) { // Sanity check
            log.error("Unexpected validity failure.");
            validateAmount();
            validateAddress();
            return;
        }
        state = State.PREPARATION;
        updateView();
        if (application.getWallet() != null) {
            onMakeTransaction(address, sendAmount);
        }
        reset();
    }

    public void onMakeTransaction(Address toAddress, Coin amount) {
        Intent intent = new Intent(getActivity(), SignTransactionActivity.class);
        try {
            if (pocket == null) {
                throw new NoSuchPocketException("No pocket found for " + type.getName());
            }

            // Decide if emptying wallet or not
            if (lastBalance != null && amount.compareTo(lastBalance) == 0) {
                intent.putExtra(Constants.ARG_EMPTY_WALLET, true);
            } else {
                intent.putExtra(Constants.ARG_SEND_VALUE, Value.valueOf(type, amount));
            }
            intent.putExtra(Constants.ARG_ACCOUNT_ID, pocket.getId());
            intent.putExtra(Constants.ARG_SEND_TO_ADDRESS, toAddress);

            startActivityForResult(intent, SIGN_TRANSACTION);
        } catch (NoSuchPocketException e) {
            Toast.makeText(getActivity(), R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
        }
    }

    private void reset() {
        sendToAddressView.setText(null);
        amountCalculatorLink.setPrimaryAmount(null);
        address = null;
        sendAmount = null;
        state = State.INPUT;
        addressError.setVisibility(View.GONE);
        amountError.setVisibility(View.GONE);
        amountWarning.setVisibility(View.GONE);
        updateView();
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN) {
            if (resultCode == Activity.RESULT_OK) {
                final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

                try {
                    final CoinURI coinUri = new CoinURI(type, input);

                    Address address = coinUri.getAddress();
                    Coin amount = coinUri.getAmount();
                    String label = coinUri.getLabel();

                    updateStateFrom(address, amount, label);
                } catch (final CoinURIParseException x) {
                    String error = getResources().getString(R.string.uri_error, x.getMessage());
                    Toast.makeText(getActivity(), error, Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == SIGN_TRANSACTION) {
            if (resultCode == Activity.RESULT_OK) {
                Exception error = (Exception) intent.getSerializableExtra(Constants.ARG_ERROR);

                if (error == null) {
                    Toast.makeText(getActivity(), R.string.sending_msg, Toast.LENGTH_SHORT).show();
                    if (listener != null) listener.onTransactionBroadcastSuccess(pocket, null);
                } else {
                    if (error instanceof InsufficientMoneyException) {
                        Toast.makeText(getActivity(), R.string.amount_error_not_enough_money_plain, Toast.LENGTH_LONG).show();
                    } else if (error instanceof NoSuchPocketException) {
                        Toast.makeText(getActivity(), R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
                    } else if (error instanceof KeyCrypterException) {
                        Toast.makeText(getActivity(), R.string.password_failed, Toast.LENGTH_LONG).show();
                    } else if (error instanceof IOException) {
                        Toast.makeText(getActivity(), R.string.send_coins_error_network, Toast.LENGTH_LONG).show();
                    } else if (error instanceof Wallet.DustySendRequested) {
                        Toast.makeText(getActivity(), R.string.send_coins_error_dust, Toast.LENGTH_LONG).show();
                    } else {
                        log.error("An unknown error occurred while sending coins", error);
                        String errorMessage = getString(R.string.send_coins_error, error.getMessage());
                        Toast.makeText(getActivity(), errorMessage, Toast.LENGTH_LONG).show();
                    }
                    if (listener != null) listener.onTransactionBroadcastFailure(pocket, null);
                }
            }
        }
    }

    void updateStateFrom(final Address address, final @Nullable Coin amount,
                         final @Nullable String label) throws CoinURIParseException {
        log.info("got {}", address);
        if (address == null) {
            throw new CoinURIParseException("missing address");
        }

        // delay these actions until fragment is resumed
        handler.post(new Runnable() {
            @Override
            public void run() {
                sendToAddressView.setText(address.toString());
                if (amount != null) amountCalculatorLink.setPrimaryAmount(type, amount);
                validateEverything();
                requestFocusFirst();
            }
        });
    }

    private void updateView() {
        sendConfirmButton.setEnabled(everythingValid());

        // enable actions
        if (scanQrCodeButton != null) {
            scanQrCodeButton.setEnabled(state == State.INPUT);
        }
    }

    private boolean isOutputsValid() {
        return address != null;
    }

    private boolean isAmountValid() {
        return isAmountValid(sendAmount);
    }

    private boolean isAmountValid(Coin amount) {
        boolean isValid = amount != null
                && amount.isPositive()
                && amount.compareTo(type.getMinNonDust()) >= 0;
        if (isValid && lastBalance != null) {
            // Check if we have the amount
            isValid = amount.compareTo(lastBalance) <= 0;
        }
        return isValid;
    }

    private boolean everythingValid() {
        return state == State.INPUT && isOutputsValid() && isAmountValid();
    }

    private void requestFocusFirst() {
        if (!isOutputsValid()) {
            sendToAddressView.requestFocus();
        } else if (!isAmountValid()) {
            amountCalculatorLink.requestFocus();
            // FIXME causes problems in older Androids
//            Keyboard.focusAndShowKeyboard(sendAmountView, getActivity());
        } else if (everythingValid()) {
            sendConfirmButton.requestFocus();
        } else {
            log.warn("unclear focus");
        }
    }

    private void validateEverything() {
        validateAddress();
        validateAmount();
    }

    private void validateAmount() {
        validateAmount(false);
    }

    private void validateAmount(boolean isTyping) {
        Coin amountParsed = amountCalculatorLink.getPrimaryAmountCoin();

        if (isAmountValid(amountParsed)) {
            sendAmount = amountParsed;
            amountError.setVisibility(View.GONE);
            // Show warning that fees apply when entered the full amount inside the pocket
            if (sendAmount != null && lastBalance != null && sendAmount.compareTo(lastBalance) == 0) {
                amountWarning.setText(R.string.amount_warn_fees_apply);
                amountWarning.setVisibility(View.VISIBLE);
            } else {
                amountWarning.setVisibility(View.GONE);
            }
        } else {
            amountWarning.setVisibility(View.GONE);
            // ignore printing errors for null and zero amounts
            if (shouldShowErrors(isTyping, amountParsed)) {
                sendAmount = null;
                if (amountParsed == null) {
                    amountError.setText(R.string.amount_error);
                } else if (amountParsed.isNegative()) {
                    amountError.setText(R.string.amount_error_negative);
                } else if (amountParsed.compareTo(type.getMinNonDust()) < 0) {
                    String minAmount = GenericUtils.formatCoinValue(type, type.getMinNonDust());
                    String message = getResources().getString(R.string.amount_error_too_small,
                            minAmount, type.getSymbol());
                    amountError.setText(message);
                } else if (lastBalance != null && amountParsed.compareTo(lastBalance) > 0) {
                    String balance = GenericUtils.formatCoinValue(type, lastBalance);
                    String message = getResources().getString(R.string.amount_error_not_enough_money,
                            balance, type.getSymbol());
                    amountError.setText(message);
                } else { // Should not happen, but show a generic error
                    amountError.setText(R.string.amount_error);
                }
                amountError.setVisibility(View.VISIBLE);
            } else {
                amountError.setVisibility(View.GONE);
            }
        }
        updateView();
    }

    /**
     * Decide if should show errors in the UI.
     */
    private boolean shouldShowErrors(boolean isTyping, Coin amountParsed) {
        if (amountParsed != null && lastBalance != null && amountParsed.compareTo(lastBalance) >= 0)
            return true;

        if (isTyping) return false;
        if (amountCalculatorLink.isEmpty()) return false;
        if (amountParsed != null && amountParsed.isZero()) return false;

        return true;
    }

    private void validateAddress() {
        validateAddress(false);
    }

    private void validateAddress(boolean isTyping) {
        String addressStr = sendToAddressView.getText().toString().trim();

        // If not typing, try to fix address if needed
        if (!isTyping) {
            addressStr = GenericUtils.fixAddress(addressStr);
            // Remove listener before changing input, then add it again. Hack to avoid stack overflow
            sendToAddressView.removeTextChangedListener(receivingAddressListener);
            sendToAddressView.setText(addressStr);
            sendToAddressView.addTextChangedListener(receivingAddressListener);
        }

        try {
            if (!addressStr.isEmpty()) {
                address = new Address(type, addressStr);
            } else {
                // empty field should not raise error message
                address = null;
            }
            addressError.setVisibility(View.GONE);
        } catch (final AddressFormatException x) {
            // could not decode address at all
            if (!isTyping) {
                address = null;
                addressError.setText(R.string.address_error);
                addressError.setVisibility(View.VISIBLE);
            }
        }

        updateView();
    }

    private void setAmountForEmptyWallet() {
        updateBalance();
        if (state != State.INPUT || pocket == null || lastBalance == null) return;

        if (lastBalance.isZero()) {
            String balance = GenericUtils.formatCoinValue(type, lastBalance);
            String message = getResources().getString(R.string.amount_error_not_enough_money,
                    balance, type.getSymbol());
            Toast.makeText(getActivity(), balance,
                    Toast.LENGTH_LONG).show();
        } else {
            amountCalculatorLink.setPrimaryAmount(type, lastBalance);
            validateAmount();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!mNavigationDrawerFragment.isDrawerOpen()) {
            // Only show items in the action bar relevant to this screen
            // if the drawer is not showing. Otherwise, let the drawer
            // decide what to show in the action bar.
            inflater.inflate(R.menu.send, menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_empty_wallet:
                setAmountForEmptyWallet();
                return true;
            default:
                // Not one of ours. Perform default menu processing
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            this.listener = (Listener) activity;
            this.application = (WalletApplication) activity.getApplication();
            this.config = application.getConfiguration();
            this.loaderManager = getLoaderManager();
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement " + Listener.class);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    public interface Listener {
        public void onTransactionBroadcastSuccess(WalletAccount pocket, Transaction transaction);
        public void onTransactionBroadcastFailure(WalletAccount pocket, Transaction transaction);
    }

    private abstract class EditViewListener implements View.OnFocusChangeListener, TextWatcher {
        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
        }
    }

    EditViewListener receivingAddressListener = new EditViewListener() {
        @Override
        public void onFocusChange(final View v, final boolean hasFocus) {
            if (!hasFocus) {
                validateAddress();
            }
        }

        @Override
        public void afterTextChanged(final Editable s) {
            validateAddress(true);
        }
    };

    private final AmountEditView.Listener amountsListener = new AmountEditView.Listener() {
        @Override
        public void changed() {
            validateAmount(true);
        }

        @Override
        public void focusChanged(final boolean hasFocus) {
            if (!hasFocus) {
                validateAmount();
            }
        }
    };

    private final LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            String localSymbol = config.getExchangeCurrencyCode();
            String coinSymbol = type.getSymbol();
            return new ExchangeRateLoader(getActivity(), config, localSymbol, coinSymbol);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);
                handler.sendMessage(handler.obtainMessage(UPDATE_EXCHANGE_RATE, exchangeRate.rate));
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) { }
    };

    private void onExchangeRateUpdate(com.reddcoin.core.util.ExchangeRate rate) {
        if (state == State.INPUT) {
            amountCalculatorLink.setExchangeRate(rate);
        }
    }

    private void onWalletUpdate() {
        updateBalance();
        validateAmount();
    }

    private final ThrottlingWalletChangeListener transactionChangeListener = new ThrottlingWalletChangeListener() {
        @Override
        public void onThrottledWalletChanged() {
            handler.sendMessage(handler.obtainMessage(UPDATE_WALLET_CHANGE));
        }
    };

    private final LoaderCallbacks<Cursor> receivingAddressLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            final String constraint = args != null ? args.getString("constraint") : null;
            Uri uri = AddressBookProvider.contentUri(application.getPackageName(), type);
            return new CursorLoader(application, uri, null, AddressBookProvider.SELECTION_QUERY,
                    new String[]{constraint != null ? constraint : ""}, null);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> cursor, final Cursor data) {
            sendToAddressViewAdapter.swapCursor(data);
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> cursor) {
            sendToAddressViewAdapter.swapCursor(null);
        }
    };

    private final class ReceivingAddressViewAdapter extends CursorAdapter implements FilterQueryProvider {
        public ReceivingAddressViewAdapter(final Context context) {
            super(context, null, false);
            setFilterQueryProvider(this);
        }

        @Override
        public View newView(final Context context, final Cursor cursor, final ViewGroup parent) {
            final LayoutInflater inflater = LayoutInflater.from(context);
            return inflater.inflate(R.layout.address_book_row, parent, false);
        }

        @Override
        public void bindView(final View view, final Context context, final Cursor cursor) {
            final String label = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_LABEL));
            final String address = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));

            final ViewGroup viewGroup = (ViewGroup) view;
            final TextView labelView = (TextView) viewGroup.findViewById(R.id.address_book_row_label);
            labelView.setText(label);
            final TextView addressView = (TextView) viewGroup.findViewById(R.id.address_book_row_address);
            addressView.setText(GenericUtils.addressSplitToGroupsMultiline(address));
        }

        @Override
        public CharSequence convertToString(final Cursor cursor) {
            return cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));
        }

        @Override
        public Cursor runQuery(final CharSequence constraint) {
            final Bundle args = new Bundle();
            if (constraint != null)
                args.putString("constraint", constraint.toString());
            loaderManager.restartLoader(ID_RECEIVING_ADDRESS_LOADER, args, receivingAddressLoaderCallbacks);
            return getCursor();
        }
    }
}
