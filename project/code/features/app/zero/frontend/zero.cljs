(ns features.app.zero.frontend.zero
  (:require
   [features.app.homepage.routes :as homepage-routes]
   [features.app.superadmin.routes :as superadmin-routes]
   [features.app.login.routes :as login-routes] 
   [features.app.workspace.routes :as workspace-routes]
   [features.app.material-templates.routes :as material-templates-routes]
   [features.app.addresses.routes :as addresses-routes]
   [features.app.customers.routes :as customers-routes]
   [features.app.teams.routes :as teams-routes]
   [features.app.worksheets.routes :as worksheets-routes]
   [features.app.settings.routes :as settings-routes]
   [router.frontend.zero :as router]
   [zero.frontend.re-frame-viewer.view :as re-frame-viewer]
   [ui.header :as header]))

(def routes homepage-routes/routes)

(defn view []
  (let [router-data (:data @router/state)
        current-path (:path @router/state)
        show-header? (and (not= current-path "/login") 
                          (not= current-path "/"))]
    [:div
     (when show-header?
       [header/view])
     [(:view router-data)]
     [re-frame-viewer/re-frame-viewer]
     [re-frame-viewer/keyboard-listener]]))
