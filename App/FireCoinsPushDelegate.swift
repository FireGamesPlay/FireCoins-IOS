import Foundation
import UIKit
import UserNotifications
import FirebaseCore
import FirebaseMessaging

extension Notification.Name {
    static let fireCoinsPushTokenUpdated = Notification.Name("FireCoinsPushTokenUpdated")
    static let fireCoinsPushOpened = Notification.Name("FireCoinsPushOpened")
}

enum FireCoinsPushCoordinator {
    private static let tokenKey = "firecoins.fcm.token"
    private static let deviceIDKey = "firecoins.push.device-id"

    static var currentToken: String? {
        UserDefaults.standard.string(forKey: tokenKey)
    }

    static var deviceID: String {
        if let stored = UserDefaults.standard.string(forKey: deviceIDKey), !stored.isEmpty {
            return stored
        }
        let value = UUID().uuidString.lowercased()
        UserDefaults.standard.set(value, forKey: deviceIDKey)
        return value
    }

    static func storeToken(_ token: String) {
        guard !token.isEmpty else { return }
        UserDefaults.standard.set(token, forKey: tokenKey)
    }

    static func requestAuthorizationIfConfigured() {
        guard FirebaseApp.app() != nil else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }
}

final class FireCoinsPushDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        configureFirebaseIfAvailable()
        guard FirebaseApp.app() != nil else { return true }
        UNUserNotificationCenter.current().delegate = self
        Messaging.messaging().delegate = self
        return true
    }

    private func configureFirebaseIfAvailable() {
        guard FirebaseApp.app() == nil,
              let info = Bundle.main.infoDictionary,
              let appID = info["FIREBASE_APP_ID"] as? String,
              let senderID = info["FIREBASE_SENDER_ID"] as? String,
              let apiKey = info["FIREBASE_API_KEY"] as? String,
              let projectID = info["FIREBASE_PROJECT_ID"] as? String,
              !appID.isEmpty, !senderID.isEmpty, !apiKey.isEmpty, !projectID.isEmpty,
              !appID.contains("$("), !senderID.contains("$("),
              !apiKey.contains("$("), !projectID.contains("$(") else {
            return
        }

        let options = FirebaseOptions(googleAppID: appID, gcmSenderID: senderID)
        options.apiKey = apiKey
        options.projectID = projectID
        options.bundleID = Bundle.main.bundleIdentifier
        FirebaseApp.configure(options: options)
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        guard FirebaseApp.app() != nil else { return }
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        NSLog("Fire Coins remote notification registration failed: %@", error.localizedDescription)
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken, !token.isEmpty else { return }
        FireCoinsPushCoordinator.storeToken(token)
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: .fireCoinsPushTokenUpdated,
                object: nil,
                userInfo: ["token": token]
            )
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        NotificationCenter.default.post(
            name: .fireCoinsPushOpened,
            object: nil,
            userInfo: response.notification.request.content.userInfo
        )
        completionHandler()
    }
}
