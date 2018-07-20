package org.thoughtcrime.securesms.util;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.crypto.SessionUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.push.ContactDiscoveryServiceTrustStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingJoinedMessage;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.contacts.crypto.Quote;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedQuoteException;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedResponseException;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class DirectoryHelper {

  private static final String TAG = DirectoryHelper.class.getSimpleName();

  private static final int CONTACT_BATCH_SIZE = 2048;

  public static void refreshDirectory(@NonNull Context context, boolean notifyOfNewUsers)
      throws IOException
  {
    if (TextUtils.isEmpty(TextSecurePreferences.getLocalNumber(context))) return;
    if (!Permissions.hasAll(context, Manifest.permission.WRITE_CONTACTS)) return;

    List<Address> newlyActiveUsers = refreshDirectory(context, AccountManagerFactory.createManager(context));

    if (TextSecurePreferences.isMultiDevice(context)) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceContactUpdateJob(context));
    }

    if (notifyOfNewUsers) notifyNewUsers(context, newlyActiveUsers);
  }

  private static @NonNull List<Address> refreshDirectory(@NonNull Context context, @NonNull SignalServiceAccountManager accountManager)
      throws IOException
  {
    if (TextUtils.isEmpty(TextSecurePreferences.getLocalNumber(context))) {
      return new LinkedList<>();
    }

    if (!Permissions.hasAll(context, Manifest.permission.WRITE_CONTACTS)) {
      return new LinkedList<>();
    }

    RecipientDatabase recipientDatabase                       = DatabaseFactory.getRecipientDatabase(context);
    Stream<String>    eligibleRecipientDatabaseContactNumbers = Stream.of(recipientDatabase.getAllAddresses()).filter(Address::isPhone).map(Address::toPhoneString);
    Stream<String>    eligibleSystemDatabaseContactNumbers    = Stream.of(ContactAccessor.getInstance().getAllContactsWithNumbers(context)).map(Address::serialize);
    Set<String>       dedupedEligibleContactNumbers           = Stream.concat(eligibleRecipientDatabaseContactNumbers, eligibleSystemDatabaseContactNumbers).collect(Collectors.toSet());
    List<String>      eligibleContactNumbers                  = new ArrayList<>(dedupedEligibleContactNumbers);

    List<Address> allActiveAddresses   = new LinkedList<>();
    List<Address> newlyActiveAddresses = new LinkedList<>();

    for (int i = 0; i < eligibleContactNumbers.size(); i += CONTACT_BATCH_SIZE) {
      List<String>  numbers      = eligibleContactNumbers.subList(i, Math.min(eligibleContactNumbers.size(), i + CONTACT_BATCH_SIZE));
      RefreshResult result       = refreshDirectory(context, accountManager, recipientDatabase, new HashSet<>(numbers));

      allActiveAddresses.addAll(result.getAllActiveAddresses());
      newlyActiveAddresses.addAll(result.getNewlyActiveAddresses());
    }

    updateContactsDatabase(context, allActiveAddresses, true);

    if (TextSecurePreferences.hasSuccessfullyRetrievedDirectory(context)) {
      return newlyActiveAddresses;
    } else {
      TextSecurePreferences.setHasSuccessfullyRetrievedDirectory(context, true);
      return new LinkedList<>();
    }
  }

  private static RefreshResult refreshDirectory(@NonNull Context                     context,
                                                @NonNull SignalServiceAccountManager accountManager,
                                                @NonNull RecipientDatabase           recipientDatabase,
                                                @NonNull Set<String>                 numbers)
      throws IOException
  {
    List<ContactTokenDetails> activeTokens = accountManager.getContacts(numbers);

    if (activeTokens != null) {
      List<Address> activeAddresses   = new LinkedList<>();
      List<Address> inactiveAddresses = new LinkedList<>();

      Set<String>  inactiveContactNumbers = new HashSet<>(numbers);

      for (ContactTokenDetails activeToken : activeTokens) {
        activeAddresses.add(Address.fromSerialized(activeToken.getNumber()));
        inactiveContactNumbers.remove(activeToken.getNumber());
      }

      for (String inactiveContactNumber : inactiveContactNumbers) {
        inactiveAddresses.add(Address.fromSerialized(inactiveContactNumber));
      }

      Set<Address>  currentActiveAddresses = new HashSet<>(recipientDatabase.getRegistered());
      Set<Address>  contactAddresses       = new HashSet<>(recipientDatabase.getSystemContacts());
      List<Address> newlyActiveAddresses   = Stream.of(activeAddresses)
                                                   .filter(address -> !currentActiveAddresses.contains(address))
                                                   .filter(contactAddresses::contains)
                                                   .toList();

      recipientDatabase.setRegistered(activeAddresses, inactiveAddresses);

      crossCheckWithNewContactDiscoveryService(context, accountManager, numbers, activeAddresses);

      return new RefreshResult(activeAddresses, newlyActiveAddresses);
    }

    return new RefreshResult(Collections.emptyList(), Collections.emptyList());
  }

  public static RegisteredState refreshDirectoryFor(@NonNull  Context context,
                                                    @NonNull  Recipient recipient)
      throws IOException
  {
    RecipientDatabase             recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    SignalServiceAccountManager   accountManager    = AccountManagerFactory.createManager(context);
    boolean                       activeUser        = recipient.resolve().getRegistered() == RegisteredState.REGISTERED;
    boolean                       systemContact     = recipient.isSystemContact();
    String                        number            = recipient.getAddress().serialize();
    Optional<ContactTokenDetails> details           = accountManager.getContact(number);

    if (details.isPresent()) {
      recipientDatabase.setRegistered(recipient, RegisteredState.REGISTERED);

      if (Permissions.hasAll(context, Manifest.permission.WRITE_CONTACTS)) {
        updateContactsDatabase(context, Util.asList(recipient.getAddress()), false);
      }

      if (!activeUser && TextSecurePreferences.isMultiDevice(context)) {
        ApplicationContext.getInstance(context).getJobManager().add(new MultiDeviceContactUpdateJob(context));
      }

      if (!activeUser && systemContact && !TextSecurePreferences.getNeedsSqlCipherMigration(context)) {
        notifyNewUsers(context, Collections.singletonList(recipient.getAddress()));
      }

      crossCheckWithNewContactDiscoveryService(context, accountManager, Collections.singleton(number), Collections.singletonList(recipient.getAddress()));

      return RegisteredState.REGISTERED;
    } else {
      recipientDatabase.setRegistered(recipient, RegisteredState.NOT_REGISTERED);
      crossCheckWithNewContactDiscoveryService(context, accountManager, Collections.singleton(number), Collections.emptyList());
      return RegisteredState.NOT_REGISTERED;
    }
  }

  private static void updateContactsDatabase(@NonNull Context context, @NonNull List<Address> activeAddresses, boolean removeMissing) {
    Optional<AccountHolder> account = getOrCreateAccount(context);

    if (account.isPresent()) {
      try {
        DatabaseFactory.getContactsDatabase(context).removeDeletedRawContacts(account.get().getAccount());
        DatabaseFactory.getContactsDatabase(context).setRegisteredUsers(account.get().getAccount(), activeAddresses, removeMissing);

        Cursor                                 cursor = ContactAccessor.getInstance().getAllSystemContacts(context);
        RecipientDatabase.BulkOperationsHandle handle = DatabaseFactory.getRecipientDatabase(context).resetAllSystemContactInfo();

        try {
          while (cursor != null && cursor.moveToNext()) {
            String number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));

            if (!TextUtils.isEmpty(number)) {
              Address   address         = Address.fromExternal(context, number);
              String    displayName     = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
              String    contactPhotoUri = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI));
              String    contactLabel    = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL));
              Uri       contactUri      = ContactsContract.Contacts.getLookupUri(cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)),
                                                                                 cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)));

              handle.setSystemContactInfo(address, displayName, contactPhotoUri, contactLabel, contactUri.toString());
            }
          }
        } finally {
          handle.finish();
        }

      } catch (RemoteException | OperationApplicationException e) {
        Log.w(TAG, e);
      }
    }
  }

  private static void notifyNewUsers(@NonNull  Context context,
                                     @NonNull  List<Address> newUsers)
  {
    if (!TextSecurePreferences.isNewContactsNotificationEnabled(context)) return;

    for (Address newUser: newUsers) {
      if (!SessionUtil.hasSession(context, newUser) && !Util.isOwnNumber(context, newUser)) {
        IncomingJoinedMessage  message      = new IncomingJoinedMessage(newUser);
        Optional<InsertResult> insertResult = DatabaseFactory.getSmsDatabase(context).insertMessageInbox(message);

        if (insertResult.isPresent()) {
          int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
          if (hour >= 9 && hour < 23) {
            MessageNotifier.updateNotification(context, insertResult.get().getThreadId(), true);
          } else {
            MessageNotifier.updateNotification(context, insertResult.get().getThreadId(), false);
          }
        }
      }
    }
  }

  private static Optional<AccountHolder> getOrCreateAccount(Context context) {
    AccountManager accountManager = AccountManager.get(context);
    Account[]      accounts       = accountManager.getAccountsByType("org.thoughtcrime.securesms");

    Optional<AccountHolder> account;

    if (accounts.length == 0) account = createAccount(context);
    else                      account = Optional.of(new AccountHolder(accounts[0], false));

    if (account.isPresent() && !ContentResolver.getSyncAutomatically(account.get().getAccount(), ContactsContract.AUTHORITY)) {
      ContentResolver.setSyncAutomatically(account.get().getAccount(), ContactsContract.AUTHORITY, true);
    }

    return account;
  }

  private static Optional<AccountHolder> createAccount(Context context) {
    AccountManager accountManager = AccountManager.get(context);
    Account        account        = new Account(context.getString(R.string.app_name), "org.thoughtcrime.securesms");

    if (accountManager.addAccountExplicitly(account, null, null)) {
      Log.w(TAG, "Created new account...");
      ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);
      return Optional.of(new AccountHolder(account, true));
    } else {
      Log.w(TAG, "Failed to create account!");
      return Optional.absent();
    }
  }

  private static void crossCheckWithNewContactDiscoveryService(@NonNull Context                     context,
                                                               @NonNull SignalServiceAccountManager accountManager,
                                                               @NonNull Set<String>                 requestedNumbers,
                                                               @NonNull List<Address>               registeredAddressesLegacy)
  {
    try {
      KeyStore     iasKeyStore      = getIasKeyStore(context);
      List<String> registered       = accountManager.getRegisteredUsers(iasKeyStore, requestedNumbers, BuildConfig.MRENCLAVE);
      Set<String>  registeredLegacy = Stream.of(registeredAddressesLegacy).map(Address::serialize).collect(Collectors.toSet());

      if (registered.size() == registeredLegacy.size() && registeredLegacy.containsAll(registered)) {
        accountManager.reportContactDiscoveryServiceMatch();
        Log.i(TAG, "New contact discovery service request matched existing results.");
      } else {
        accountManager.reportContactDiscoveryServiceMismatch();
        Log.w(TAG, "New contact discovery service request did NOT match existing results.");
      }

    } catch(NonSuccessfulResponseCodeException e) {
      if (e.getResponseCode() >= 400 && e.getResponseCode() < 500) {
        accountManager.reportContactDiscoveryServiceClientError();
        Log.w(TAG, "Failed due to a client error.", e);
      } else if (e.getResponseCode() >= 500 && e.getResponseCode() < 600) {
        accountManager.reportContactDiscoveryServiceServerError();
        Log.w(TAG, "Failed due to a server error.", e);
      } else {
        accountManager.reportContactDiscoveryServiceUnexpectedError();
        Log.w(TAG, "Failed with an unexpected response code.", e);
      }
    } catch (CertificateException | SignatureException | UnauthenticatedQuoteException | Quote.InvalidQuoteFormatException e) {
      accountManager.reportContactDiscoveryServiceAttestationError();
      Log.w(TAG, "Failed during attestation.", e);
    } catch (PushNetworkException e) {
      Log.w(TAG, "Failed due to poor network.", e);
    } catch (IOException | NoSuchAlgorithmException | KeyStoreException | UnauthenticatedResponseException e) {
      accountManager.reportContactDiscoveryServiceUnexpectedError();
      Log.w(TAG, "Failed for an unknown reason.", e);
    }
  }

  private static KeyStore getIasKeyStore(@NonNull Context context)
      throws CertificateException, NoSuchAlgorithmException, IOException, KeyStoreException
  {
    TrustStore contactTrustStore = new ContactDiscoveryServiceTrustStore(context);

    KeyStore keyStore = KeyStore.getInstance("BKS");
    keyStore.load(contactTrustStore.getKeyStoreInputStream(), contactTrustStore.getKeyStorePassword().toCharArray());

    return keyStore;
  }


  private static class AccountHolder {

    private final boolean fresh;
    private final Account account;

    private AccountHolder(Account account, boolean fresh) {
      this.fresh   = fresh;
      this.account = account;
    }

    @SuppressWarnings("unused")
    public boolean isFresh() {
      return fresh;
    }

    public Account getAccount() {
      return account;
    }

  }

  private static class RefreshResult {

    private final List<Address> allActiveAddresses;
    private final List<Address> newlyActiveAddresses;

    private RefreshResult(List<Address> allActiveAddresses, List<Address> newlyActiveAddresses) {
      this.allActiveAddresses = allActiveAddresses;
      this.newlyActiveAddresses = newlyActiveAddresses;
    }

    public List<Address> getAllActiveAddresses() {
      return allActiveAddresses;
    }

    public List<Address> getNewlyActiveAddresses() {
      return newlyActiveAddresses;
    }
  }
}
