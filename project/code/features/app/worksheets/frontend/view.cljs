(ns features.app.worksheets.frontend.view
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [parquery.frontend.request :as parquery]
            [router.frontend.zero :as router]
            [zero.frontend.re-frame :as rf]
            [zero.frontend.react :as zero-react]
            [ui.modal :as modal]
            [ui.form-field :as form-field]
            [ui.data-table :as data-table]
            [ui.enhanced-button :as enhanced-button]
            [ui.page-header :as page-header]
            [ui.address-search :as address-search]
            [translations.core :as tr]
            ["react-signature-canvas" :default SignatureCanvas]))

(defn- get-workspace-id
  "Get workspace ID from router parameters"
  []
  (let [router-state @router/state
        workspace-id (get-in router-state [:parameters :path :workspace-id])]
      workspace-id))

(defn- load-worksheets-query
  "Execute ParQuery to load worksheets with pagination"
  [workspace-id params worksheets-data loading?]
  (reset! loading? true)
  (parquery/send-queries
   {:queries {:workspace-worksheets/get-paginated (or params {})}
    :parquery/context {:workspace-id workspace-id}
    :callback (fn [response]
               (reset! loading? false)
               (let [result (:workspace-worksheets/get-paginated response)]
                 (reset! worksheets-data result)))}))


(defn- save-worksheet-query
  "Execute ParQuery to save worksheet"
  [worksheet workspace-id modal-is-new? callback modal-worksheet load-worksheets]
  (let [is-new? @modal-is-new?
        query-type (if is-new? :workspace-worksheets/create :workspace-worksheets/update)
        worksheet-data (if is-new? (dissoc worksheet :worksheet/id) worksheet)
        context {:workspace-id workspace-id}]
    (parquery/send-queries
     {:queries {query-type worksheet-data}
      :parquery/context context
      :callback (fn [response]
                 (callback)
                 (if (:success (get response query-type))
                   (do (reset! modal-worksheet nil)
                       (load-worksheets {}))
                   (js/alert (str "Error: " (:error (get response query-type))))))})))

(defn- delete-worksheet-query
  "Execute ParQuery to delete worksheet"
  [worksheet-id workspace-id load-worksheets]
  (parquery/send-queries
   {:queries {:workspace-worksheets/delete {:worksheet/id worksheet-id}}
    :parquery/context {:workspace-id workspace-id}
    :callback (fn [response]
               (if (:success (:workspace-worksheets/delete response))
                 (load-worksheets)
                 (js/alert "Error deleting worksheet")))}))

(defn- validate-serial-number
  "Validate worksheet serial number"
  [serial-number]
  (< (count (str/trim (str serial-number))) 2))

(defn- validate-work-description
  "Validate work description"
  [work-description]
  (< (count (str/trim (str work-description))) 5))

(defn validate-worksheet
  "Validates worksheet data and returns map of field errors"
  [worksheet]
  (let [errors {}
        serial-number (:worksheet/serial-number worksheet)
        work-description (:worksheet/work-description worksheet)
        work-type (:worksheet/work-type worksheet)
        service-type (:worksheet/service-type worksheet)
        status (:worksheet/status worksheet)]
    (cond-> errors
      (validate-work-description work-description) (assoc :worksheet/work-description "Work description is required (min 5 characters)")
      (empty? work-type) (assoc :worksheet/work-type "Work type is required")
      (empty? service-type) (assoc :worksheet/service-type "Service type is required") 
      (empty? status) (assoc :worksheet/status "Status is required")
      (empty? (:worksheet/address-id worksheet)) (assoc :worksheet/address-id "Address is required"))))

(defn- calculate-work-duration
  "Calculate work duration in hours from arrival and departure times"
  [arrival-time departure-time]
  (when (and arrival-time departure-time 
             (not (empty? arrival-time)) 
             (not (empty? departure-time)))
    (try
      (let [arrival (js/Date. arrival-time)
            departure (js/Date. departure-time)]
        (when (> (.getTime departure) (.getTime arrival))
          (let [diff-ms (- (.getTime departure) (.getTime arrival))
                diff-hours (/ diff-ms 1000 60 60)]
            ;; Round up to nearest full hour
            (Math/ceil diff-hours))))
      (catch js/Error e
        (println "Error calculating duration:" e)
        nil))))


(defn- field-label [label field-key has-error?]
  [:label {:style {:display "block" :margin-bottom "0.5rem" :font-weight "600"
                   :font-size "0.875rem" :letter-spacing "0.025em"
                   :color (if has-error? "#dc3545" "#374151")}}
   label 
   (when (#{:worksheet/work-description :worksheet/work-type :worksheet/service-type :worksheet/status} field-key) 
     [:span {:style {:color "#ef4444" :margin-left "0.25rem"}} "*"])])

(defn- format-datetime-for-input
  "Convert ISO datetime string to datetime-local format (YYYY-MM-DDTHH:mm)"
  [iso-datetime]
  (when (and iso-datetime (not (empty? iso-datetime)))
    (try
      (let [date (js/Date. iso-datetime)
            year (.getFullYear date)
            month (str (.padStart (str (inc (.getMonth date))) 2 "0"))
            day (str (.padStart (str (.getDate date)) 2 "0"))
            hours (str (.padStart (str (.getHours date)) 2 "0"))
            minutes (str (.padStart (str (.getMinutes date)) 2 "0"))]
        (str year "-" month "-" day "T" hours ":" minutes))
      (catch js/Error e
        (println "Error formatting datetime:" e)
        ""))))

(defn- input-base-props
  "Base properties for input fields"
  [field-key has-error? attrs]
  (let [is-time-field? (#{:worksheet/arrival-time :worksheet/departure-time} field-key)
        form-data-sub @(rf/subscribe [:worksheets/modal-form-data])
        form-data (or form-data-sub {})
        field-value (get form-data field-key "")
        display-value (if (and is-time-field? (= (:type attrs) "datetime-local"))
                        (format-datetime-for-input field-value)
                        (str field-value))]
    (when (nil? form-data-sub)
      (println "WARNING: form-data subscription returned nil for field:" field-key))
    (let [base-change-handler (fn [e] 
                             (let [value (.. e -target -value)]
                               (rf/dispatch [:worksheets/update-modal-form-field field-key value])
                               (when is-time-field?
                                 ;; Auto-calculate duration for time fields
                                 (let [updated-data (assoc form-data field-key value)
                                       arrival (:worksheet/arrival-time updated-data)
                                       departure (:worksheet/departure-time updated-data)
                                       calculated-duration (calculate-work-duration arrival departure)]
                                   (when calculated-duration
                                     (rf/dispatch [:worksheets/update-modal-form-field :worksheet/work-duration-hours calculated-duration]))))))]
    {:value display-value
     :on-change base-change-handler
     :style (merge {:width "100%"
                    :padding "0.75rem 1rem"
                    :border (if has-error? "2px solid #dc3545" "1px solid #d1d5db")
                    :border-radius "8px"
                    :font-size "1rem"
                    :line-height "1.5"
                    :transition "border-color 0.2s ease-in-out, box-shadow 0.2s ease-in-out"
                    :box-shadow (if has-error? 
                                  "0 0 0 3px rgba(220, 53, 69, 0.1)" 
                                  "0 1px 2px 0 rgba(0, 0, 0, 0.05)")
                    :outline "none"
                    :background (when (:disabled attrs) "#f9fafb")
                    :cursor (when (:disabled attrs) "not-allowed")}
                   (:style attrs)
                   {:focus {:border-color (if has-error? "#dc3545" "#3b82f6")
                           :box-shadow (if has-error? 
                                         "0 0 0 3px rgba(220, 53, 69, 0.1)"
                                         "0 0 0 3px rgba(59, 130, 246, 0.1)")}})})))

(defn- render-textarea
  "Render textarea input"
  [field-key has-error? attrs]
  [:textarea (merge (input-base-props field-key has-error? attrs) (dissoc attrs :type))])

(defn- render-text-input
  "Render text input"
  [field-key has-error? attrs]
  [:input (merge (input-base-props field-key has-error? attrs) attrs)])

(defn- render-select
  "Render select input"
  [field-key has-error? attrs options]
  [:select (merge (input-base-props field-key has-error? attrs) (dissoc attrs :options))
   [:option {:value ""} "Select..."]
   (for [[value label] options]
     ^{:key value}
     [:option {:value value} label])])

(defn- render-address-select
  "Render address dropdown that loads addresses from workspace"
  [field-key has-error? attrs]
  (let [workspace-id (:workspace-id attrs)
        addresses (r/atom [])
        loading? (r/atom false)]
    
    ;; Load addresses when component mounts
    (when workspace-id
      (reset! loading? true)
      (parquery/send-queries
        {:queries {:workspace-addresses/get-all {:workspace-id workspace-id}}
         :parquery/context {:workspace-id workspace-id}
         :callback (fn [response]
                     (reset! loading? false)
                     (let [addr-list (:workspace-addresses/get-all response [])]
                       (println "DEBUG: Loaded addresses:" (count addr-list))
                       (reset! addresses addr-list)))}))
    
    (fn [field-key has-error? attrs]
      [:select (merge (input-base-props field-key has-error? attrs) 
                      (dissoc attrs :workspace-id :current-value))
       [:option {:value ""} 
        (if @loading? "Loading addresses..." "Select address...")]
       (for [address @addresses]
         ^{:key (:address/id address)}
         [:option {:value (:address/id address)} 
          (:address/name address)])])))

(defn- field-input
  "Render appropriate input type"
  [field-key has-error? attrs]
  (cond
    (= (:type attrs) "textarea") (render-textarea field-key has-error? attrs)
    (= (:type attrs) "select") (render-select field-key has-error? attrs (:options attrs))
    (= (:type attrs) "address-select") [render-address-select field-key has-error? attrs]
    :else (render-text-input field-key has-error? attrs)))

(defn- field-error [error-msg]
  (when error-msg
    [:div {:style {:color "#dc3545" :font-size "0.875rem" :margin-top "0.25rem"}}
     error-msg]))

(defn- form-field
  "Complete form field with label, input and error"
  [label field-key errors attrs]
  (let [has-error? (contains? errors field-key)]
    [:div {:style {:margin-bottom "1.5rem"}}
     [field-label label field-key has-error?]
     [field-input field-key has-error? attrs]
     [field-error (get errors field-key)]]))

(defn- form-fields
  "All form input fields"
  []
  (let [errors-sub @(rf/subscribe [:worksheets/modal-form-errors])
        errors (or errors-sub {})]
    (println "form-fields called, errors-sub:" errors-sub "errors:" errors)
    [:div
     [form-field "Serial Number" :worksheet/serial-number errors
      {:type "text" :placeholder "Auto-generated" :disabled true}]
     [form-field "Creation Date" :worksheet/creation-date errors
      {:type "date"}]
     
     ;; Address search field - using the working version below in modal-form-content
         
     [form-field "Work Type" :worksheet/work-type errors
      {:type "select" :options [["repair" "Repair"] ["maintenance" "Maintenance"] ["other" "Other"]]}]
     [form-field "Service Type" :worksheet/service-type errors
      {:type "select" :options [["normal" "Normal"] ["night" "Night"] ["weekend" "Weekend"] ["holiday" "Holiday"]]}]
     [form-field "Work Description" :worksheet/work-description errors
      {:type "textarea" :placeholder "Describe the work to be performed..." :rows 4}]
     [form-field "Status" :worksheet/status errors
      {:type "select" :options [["draft" "Draft"] ["in_progress" "In Progress"] ["completed" "Completed"] ["cancelled" "Cancelled"]]}]
     [form-field "Arrival Time" :worksheet/arrival-time errors
      {:type "datetime-local"}]
     [form-field "Departure Time" :worksheet/departure-time errors
      {:type "datetime-local"}]
     [form-field "Work Duration (Hours)" :worksheet/work-duration-hours errors
      {:type "number" :step "1" :placeholder "Auto-calculated from arrival/departure" :disabled true}]
     [:div {:style {:margin-bottom "1.5rem" :font-size "0.75rem" :color "#6b7280"}}
      "💡 Work duration is automatically calculated from arrival and departure times (rounded up to nearest full hour)"]
     [form-field "Notes" :worksheet/notes errors
      {:type "textarea" :placeholder "Optional notes..." :rows 3}]]))

(defn- handle-save-click
  "Handle save button click with validation"
  [on-save]
  (let [form-data @(rf/subscribe [:worksheets/modal-form-data])
        validation-errors (validate-worksheet form-data)]
    (if (empty? validation-errors)
      (do
          (rf/dispatch [:worksheets/set-modal-form-loading true])
          (rf/dispatch [:worksheets/set-modal-form-errors {}])
          (on-save form-data (fn [] 
                              (rf/dispatch [:worksheets/set-modal-form-loading false]))))
      (do
          (rf/dispatch [:worksheets/set-modal-form-errors validation-errors])))))

(defn- modal-close-handler
  "Handle modal close event"
  [on-cancel]
  (fn []
    (println "Modal closing...")
    (rf/dispatch [:worksheets/clear-modal-form])
    (when on-cancel (on-cancel))))

(defn- modal-header-config
  "Generate modal header configuration"
  [is-new?]
  {:title (if is-new? (tr/tr :worksheets/modal-add-title) (tr/tr :worksheets/modal-edit-title))
   :subtitle (if is-new? (tr/tr :worksheets/modal-add-subtitle) (tr/tr :worksheets/modal-edit-subtitle))})

(defn- basic-form-fields
  "Render basic worksheet form fields"
  [errors]
  [:div
   [form-field "Serial Number" :worksheet/serial-number errors
    {:type "text" :placeholder "Auto-generated" :disabled true}]
   [form-field "Creation Date" :worksheet/creation-date errors
    {:type "date"}]])

(defn- address-form-field
  "Render address selection field"
  [errors]
  (let [form-data @(rf/subscribe [:worksheets/modal-form-data])
        workspace-id (get-workspace-id)]
    [form-field "Address" :worksheet/address-id errors
     {:type "address-select"
      :workspace-id workspace-id
      :current-value (:worksheet/address-id form-data)}]))

(defn- work-info-form-fields
  "Render work information form fields"
  [errors]
  [:div
   [form-field "Work Type" :worksheet/work-type errors
    {:type "select" :options [["repair" "Repair"] ["maintenance" "Maintenance"] ["other" "Other"]]}]
   [form-field "Service Type" :worksheet/service-type errors
    {:type "select" :options [["normal" "Normal"] ["night" "Night"] ["weekend" "Weekend"] ["holiday" "Holiday"]]}]
   [form-field "Work Description" :worksheet/work-description errors
    {:type "textarea" :placeholder "Describe the work to be performed..." :rows 4}]
   [form-field "Status" :worksheet/status errors
    {:type "select" :options [["draft" "Draft"] ["in_progress" "In Progress"] ["completed" "Completed"] ["cancelled" "Cancelled"]]}]])

(defn- time-tracking-form-fields
  "Render time tracking form fields"
  [errors]
  [:div
   [form-field "Arrival Time" :worksheet/arrival-time errors
    {:type "datetime-local"}]
   [form-field "Departure Time" :worksheet/departure-time errors
    {:type "datetime-local"}]
   [form-field "Work Duration (Hours)" :worksheet/work-duration-hours errors
    {:type "number" :step "1" :placeholder "Auto-calculated from arrival/departure" :disabled true}]
   [:div {:style {:margin-bottom "1.5rem" :font-size "0.75rem" :color "#6b7280"}}
    "💡 Work duration is automatically calculated from arrival and departure times (rounded up to nearest full hour)"]])

(defn- notes-form-field
  "Render notes form field"
  [errors]
  [form-field "Notes" :worksheet/notes errors
   {:type "textarea" :placeholder "Optional notes..." :rows 3}])

(defn- material-item-display
  "Render single material item with remove button"
  [idx material]
  [:div {:key idx 
         :style {:display "flex" :justify-content "space-between" :align-items "center" :padding "0.25rem 0" :border-bottom "1px solid #f3f4f6"}}
   [:span {:style {:font-size "0.875rem"}}
    (str (:name material) " - " (:quantity material) " " (:unit material))]
   [:button {:type "button"
            :on-click #(rf/dispatch [:worksheets/remove-material idx])
            :style {:background "#ef4444" :color "white" :border "none" :border-radius "4px" :padding "0.25rem 0.5rem" :font-size "0.75rem" :cursor "pointer"}}
    "Remove"]])

(defn- existing-materials-list
  "Render list of existing materials"
  [materials]
  (when (seq materials)
    [:div {:style {:margin-bottom "1rem"}}
     [:h4 {:style {:margin-bottom "0.5rem" :font-size "0.9rem" :font-weight "500" :color "#374151"}}
      "Added Materials:"]
     [:div {:style {:max-height "150px" :overflow-y "auto" :border "1px solid #d1d5db" :border-radius "6px" :padding "0.5rem"}}
      (map-indexed material-item-display materials)]]))

(defn- template-material-selector
  "Render material template selection form"
  [material-templates selected-template-id]
  [:div {:style {:display "grid" :grid-template-columns "2fr 1fr auto" :gap "0.5rem" :align-items "end"}}
   [:div
    [:label {:style {:display "block" :margin-bottom "0.25rem" :font-weight "500" :font-size "0.75rem" :color "#374151"}}
     "Select Material"]
    [:select {:value selected-template-id
             :on-change #(rf/dispatch [:worksheets/select-material-template (.. % -target -value)])
             :style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db" :border-radius "6px" :font-size "0.875rem"}}
     [:option {:value ""} "Choose material..."]
     (map (fn [template]
            [:option {:key (:material-template/id template) :value (:material-template/id template)}
             (str (:material-template/name template) " (" (:material-template/unit template) ")")])
          (sort-by :material-template/name material-templates))]]
   [:div
    [:label {:style {:display "block" :margin-bottom "0.25rem" :font-weight "500" :font-size "0.75rem" :color "#374151"}}
     "Quantity"]
    [:input {:type "number"
            :value (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/new-material-quantity "")
            :on-change #(rf/dispatch [:worksheets/update-form-field :worksheet/new-material-quantity (.. % -target -value)])
            :placeholder "5"
            :disabled (empty? selected-template-id)
            :style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db" :border-radius "6px" :font-size "0.875rem" :opacity (if (empty? selected-template-id) 0.5 1)}}]]
   [:button {:type "button"
            :on-click #(rf/dispatch [:worksheets/add-selected-material])
            :disabled (or (empty? selected-template-id) 
                         (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/new-material-quantity ""))))
            :style {:background (if (or (empty? selected-template-id) 
                                       (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/new-material-quantity ""))))
                                 "#9ca3af" "#10b981")
                   :color "white" :border "none" :border-radius "6px" :padding "0.5rem 1rem" :font-size "0.875rem" :cursor (if (or (empty? selected-template-id) 
                                                                                                                                    (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/new-material-quantity "")))) 
                                                                                                                                "not-allowed" "pointer")
                   :font-weight "500"}}
    "Add"]])

(defn- custom-material-inputs
  "Render custom material input form"
  []
  [:div {:style {:margin-top "1rem" :padding-top "1rem" :border-top "1px solid #e5e7eb"}}
   [:h4 {:style {:margin-bottom "0.5rem" :font-size "0.9rem" :font-weight "500" :color "#374151"}}
    "Add Custom Material:"]
   [:div {:style {:display "grid" :grid-template-columns "2fr 1fr 1fr auto" :gap "0.5rem" :align-items "end"}}
    [:div
     [:label {:style {:display "block" :margin-bottom "0.25rem" :font-weight "500" :font-size "0.75rem" :color "#374151"}}
      "Custom Material Name"]
     [:input {:type "text"
             :value (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-name "")
             :on-change #(rf/dispatch [:worksheets/update-form-field :worksheet/custom-material-name (.. % -target -value)])
             :placeholder "Enter material name..."
             :style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db" :border-radius "6px" :font-size "0.875rem"}}]]
    [:div
     [:label {:style {:display "block" :margin-bottom "0.25rem" :font-weight "500" :font-size "0.75rem" :color "#374151"}}
      "Unit"]
     [:input {:type "text"
             :value (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-unit "")
             :on-change #(rf/dispatch [:worksheets/update-form-field :worksheet/custom-material-unit (.. % -target -value)])
             :placeholder "pcs, kg, m..."
             :style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db" :border-radius "6px" :font-size "0.875rem"}}]]
    [:div
     [:label {:style {:display "block" :margin-bottom "0.25rem" :font-weight "500" :font-size "0.75rem" :color "#374151"}}
      "Quantity"]
     [:input {:type "number"
             :value (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-quantity "")
             :on-change #(rf/dispatch [:worksheets/update-form-field :worksheet/custom-material-quantity (.. % -target -value)])
             :placeholder "5"
             :style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db" :border-radius "6px" :font-size "0.875rem"}}]]
    [:button {:type "button"
             :on-click #(rf/dispatch [:worksheets/add-custom-material])
             :disabled (or (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-name "")))
                          (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-unit "")))
                          (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-quantity ""))))
             :style {:background (if (or (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-name "")))
                                        (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-unit "")))
                                        (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-quantity ""))))
                                  "#9ca3af" "#2563eb")
                    :color "white" :border "none" :border-radius "6px" :padding "0.5rem 1rem" :font-size "0.875rem" 
                    :cursor (if (or (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-name "")))
                                   (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-unit "")))
                                   (empty? (str (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/custom-material-quantity ""))))
                             "not-allowed" "pointer")
                    :font-weight "500"}}
     "Add"]]])

(defn- materials-section
  "Render complete materials section"
  []
  (let [materials (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/material-usage [])
        material-templates @(rf/subscribe [:material-templates/all])
        selected-template-id (get @(rf/subscribe [:worksheets/modal-form-data]) :worksheet/selected-material-template "")]
    [:div {:style {:margin-bottom "1.5rem"}}
     [:h3 {:style {:margin-bottom "1rem" :font-size "1.125rem" :font-weight "600" :color "#374151"}}
      "Materials Used"]
     [:div
      [existing-materials-list materials]
      [template-material-selector material-templates selected-template-id]
      [custom-material-inputs]]]))

(defn- signature-display
  "Render signature as clickable display (non-active)"
  [ref-dispatch-key label]
  (let [form-data @(rf/subscribe [:worksheets/modal-form-data])
        signature-data (if (= ref-dispatch-key :worksheets/set-maintainer-signature-ref)
                         (:worksheet/maintainer-signature form-data)
                         (:worksheet/customer-signature form-data))]
    (println "DEBUG signature-display:" label "has signature data:" (boolean signature-data))
    [:div
     [:label {:style {:display "block" :margin-bottom "0.5rem" :font-weight "600" :font-size "0.875rem" :color "#374151"}}
      label]
     [:div {:style {:border "1px solid #d1d5db"
                    :border-radius "8px"
                    :background "#ffffff"
                    :width "100%"
                    :max-width "300px"
                    :height "150px"
                    :cursor "pointer"
                    :display "flex"
                    :align-items "center"
                    :justify-content "center"
                    :position "relative"}
            :on-click #(rf/dispatch [:worksheets/open-signature-zoom label])}
      (if signature-data
        (do
          (println "DEBUG signature-display: showing signature for" label)
          [:img {:src signature-data
                 :style {:max-width "100%"
                         :max-height "100%"
                         :object-fit "contain"}}])
        [:div {:style {:color "#9ca3af" :text-align "center" :font-size "0.875rem"}}
         "Click to sign"])
      [:div {:style {:position "absolute"
                     :top "5px"
                     :right "5px"
                     :background "rgba(0,0,0,0.5)"
                     :color "white"
                     :border-radius "3px"
                     :padding "2px 6px"
                     :font-size "0.75rem"}}
       "🔍"]]]))

(defn- signature-canvas
  "Render signature canvas for editing (used in overlay)"
  [ref-dispatch-key label]
  [:div
   [:label {:style {:display "block" :margin-bottom "0.5rem" :font-weight "600" :font-size "0.875rem" :color "#374151"}}
    label]
   [:> SignatureCanvas
    {:penColor "black"
     :canvasProps {:width 300
                   :height 150
                   :style {:border "1px solid #d1d5db"
                           :border-radius "8px"
                           :background "#ffffff"
                           :display "block"
                           :width "100%"
                           :max-width "300px"}}
     :ref (fn [ref] (rf/dispatch [ref-dispatch-key ref]))}]])

(defn- signature-clear-buttons
  "Render signature clear buttons"
  []
  [:div {:style {:display "flex" :justify-content "space-between" :margin-top "0.5rem"}}
   [:button {:type "button"
            :on-click (fn []
                        (when-let [ref @(rf/subscribe [:worksheets/maintainer-signature-ref])]
                          (.clear ref)))
            :style {:padding "0.25rem 0.75rem" :font-size "0.75rem" :color "#6b7280" :background "transparent" :border "1px solid #d1d5db" :border-radius "4px" :cursor "pointer"}}
    "Clear Maintainer"]
   [:button {:type "button"
            :on-click (fn []
                        (when-let [ref @(rf/subscribe [:worksheets/customer-signature-ref])]
                          (.clear ref)))
            :style {:padding "0.25rem 0.75rem" :font-size "0.75rem" :color "#6b7280" :background "transparent" :border "1px solid #d1d5db" :border-radius "4px" :cursor "pointer"}}
    "Clear Customer"]])

(defn- signature-zoom-overlay
  "Full-screen overlay for signature editing"
  []
  (let [zoom-data @(rf/subscribe [:worksheets/signature-zoom-data])]
    (when zoom-data
      [:div {:style {:position "fixed"
                     :top 0
                     :left 0
                     :width "100vw"
                     :height "100vh"
                     :background "rgba(0, 0, 0, 0.8)"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 9999}
             :on-click #(rf/dispatch [:worksheets/close-signature-zoom])}
       [:div {:style {:background "white"
                      :border-radius "12px"
                      :padding "2rem"
                      :width "90vw"
                      :max-width "600px"
                      :height "auto"
                      :max-height "80vh"
                      :position "relative"
                      :overflow "auto"}
              :on-click (fn [e] (.stopPropagation e))}
        ;; Close button
        [:button {:style {:position "absolute"
                          :top "10px"
                          :right "10px"
                          :background "transparent"
                          :border "none"
                          :font-size "1.5rem"
                          :cursor "pointer"
                          :color "#6b7280"
                          :width "30px"
                          :height "30px"
                          :display "flex"
                          :align-items "center"
                          :justify-content "center"}
                  :on-click #(rf/dispatch [:worksheets/close-signature-zoom])}
         "×"]
        
        ;; Content
        [:h2 {:style {:margin "0 0 1.5rem 0" :font-size "1.5rem" :font-weight "600" :color "#374151"}}
         (:label zoom-data)]
        
        ;; Signature canvas - larger for mobile/desktop
        [:div {:style {:margin-bottom "1.5rem"}}
         [:> SignatureCanvas
          {:penColor "black"
           :canvasProps {:width (if (< js/window.innerWidth 768) 
                                  (- js/window.innerWidth 80) 
                                  500)
                         :height (if (< js/window.innerWidth 768) 
                                   250 
                                   300)
                         :style {:border "2px solid #d1d5db"
                                 :border-radius "8px"
                                 :background "#ffffff"
                                 :display "block"
                                 :width "100%"}}
           :ref (fn [ref] 
                  (rf/dispatch [:worksheets/set-zoom-signature-ref ref])
                  ;; Load existing signature data into zoom canvas
                  (when ref
                    (let [form-data @(rf/subscribe [:worksheets/modal-form-data])
                          signature-data (if (= (:ref-dispatch-key zoom-data) :worksheets/set-maintainer-signature-ref)
                                           (:worksheet/maintainer-signature form-data)
                                           (:worksheet/customer-signature form-data))]
                      (when signature-data
                        (println "DEBUG zoom canvas: loading existing signature data")
                        (.fromDataURL ref signature-data)))))}]]
        
        ;; Action buttons
        [:div {:style {:display "flex" :justify-content "space-between" :gap "1rem"}}
         [:button {:type "button"
                   :on-click (fn []
                               (when-let [ref @(rf/subscribe [:worksheets/zoom-signature-ref])]
                                 (.clear ref)))
                   :style {:padding "0.75rem 1.5rem" :font-size "0.875rem" :color "#6b7280" 
                           :background "transparent" :border "1px solid #d1d5db" :border-radius "6px" 
                           :cursor "pointer" :font-weight "500"}}
          "Clear"]
         [:button {:type "button"
                   :on-click #(rf/dispatch [:worksheets/close-signature-zoom])
                   :style {:padding "0.75rem 1.5rem" :font-size "0.875rem" :color "white"
                           :background "#3b82f6" :border "none" :border-radius "6px"
                           :cursor "pointer" :font-weight "500"}}
          "Done"]]]])))

(defn- signatures-section
  "Render complete signatures section"
  []
  [:div {:style {:margin-bottom "1.5rem"}}
   [:h3 {:style {:margin-bottom "1rem" :font-size "1.125rem" :font-weight "600" :color "#374151"}}
    "Signatures"]
   [:div {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "1rem"}}
    [signature-display :worksheets/set-maintainer-signature-ref "Maintainer Signature"]
    [signature-display :worksheets/set-customer-signature-ref "Customer Signature"]]
   
   ])


(defn- save-button-click-handler
  "Handle save button click with validation and signature capture"
  [on-save]
  (fn []
    (let [form-data @(rf/subscribe [:worksheets/modal-form-data])
          maintainer-signature (:worksheet/maintainer-signature form-data)
          customer-signature (:worksheet/customer-signature form-data)]
      (println "DEBUG save-button: maintainer signature length:" (when maintainer-signature (count maintainer-signature)))
      (println "DEBUG save-button: customer signature length:" (when customer-signature (count customer-signature)))
      (println "DEBUG save-button: form-data keys:" (keys form-data))
      (println "DEBUG save-button: has maintainer signature?" (boolean maintainer-signature))
      (println "DEBUG save-button: has customer signature?" (boolean customer-signature))
      (let [validation-errors (validate-worksheet form-data)]
        (if (empty? validation-errors)
          (do
            (rf/dispatch [:worksheets/set-modal-form-loading true])
            (rf/dispatch [:worksheets/set-modal-form-errors {}])
            (on-save form-data (fn [] (rf/dispatch [:worksheets/set-modal-form-loading false]))))
          (rf/dispatch [:worksheets/set-modal-form-errors validation-errors]))))))

(defn- modal-footer-buttons
  "Render modal footer buttons"
  [on-cancel on-save]
  [modal/modal-footer
   [enhanced-button/enhanced-button
    {:variant :secondary
     :on-click (fn []
                (println "Cancel button clicked")
                (rf/dispatch [:worksheets/clear-modal-form])
                (when on-cancel (on-cancel)))
     :text (tr/tr :worksheets/cancel)}]
   [enhanced-button/enhanced-button
    {:variant :primary
     :loading? @(rf/subscribe [:worksheets/modal-form-loading?])
     :on-click (save-button-click-handler on-save)
     :text (if @(rf/subscribe [:worksheets/modal-form-loading?]) (tr/tr :worksheets/saving) (tr/tr :worksheets/save-worksheet))}]])

(defn worksheet-modal
  "Modal for creating/editing worksheets using re-frame"
  [worksheet-data is-new? on-save on-cancel]
  (println "worksheet-modal called with is-new?:" is-new? "type:" (type is-new?))
  ;; Initialize form state in re-frame only once
  (rf/dispatch [:worksheets/set-modal-form-data worksheet-data])
  (rf/dispatch [:worksheets/set-modal-form-errors {}])
  (rf/dispatch [:worksheets/set-modal-form-loading false])
  (rf/dispatch [:material-templates/load (get-workspace-id)])
  
  (fn [worksheet-data is-new? on-save on-cancel]
    (println "worksheet-modal render function called")
    [:div
     [modal/modal {:on-close (modal-close-handler on-cancel) 
                   :close-on-backdrop? true}
      [modal/modal-header (modal-header-config is-new?)]
      (let [errors @(rf/subscribe [:worksheets/modal-form-errors])]
        [:div {:style {:padding "20px"}}
         [basic-form-fields errors]
         [address-form-field errors]
         [work-info-form-fields errors]
         [time-tracking-form-fields errors]
         [notes-form-field errors]
         [materials-section]
         [signatures-section]])
      [modal-footer-buttons on-cancel on-save]]
     [signature-zoom-overlay]]))

(defn- worksheet-serial-render
  "Custom render function for worksheet serial number column"
  [serial-number row]
  [:div {:style {:font-weight "600" :color "#111827" :font-size "0.875rem"}}
   serial-number])

(defn- work-type-render
  "Custom render function for work type column with service type"
  [work-type row]
  [:div
   [:div {:style {:font-weight "500" :color "#111827" :font-size "0.875rem"
                  :text-transform "capitalize"}}
    work-type]
   [:div {:style {:color "#6b7280" :font-size "0.75rem" :margin-top "0.25rem"
                  :text-transform "capitalize"}}
    (str "Service: " (:worksheet/service-type row))]])

(defn- status-render
  "Custom render function for status column with colored badges"
  [status row]
  (let [status-colors {"draft" {:bg "#fef3c7" :color "#92400e"}
                      "in_progress" {:bg "#dbeafe" :color "#1e40af"}
                      "completed" {:bg "#d1fae5" :color "#065f46"}
                      "cancelled" {:bg "#fee2e2" :color "#991b1b"}}
        colors (get status-colors status {:bg "#f3f4f6" :color "#374151"})]
    [:span {:style {:display "inline-block" :padding "0.25rem 0.75rem"
                    :background (:bg colors) :color (:color colors)
                    :border-radius "12px" :font-size "0.75rem" :font-weight "500"
                    :text-transform "capitalize"}}
     (str/replace status "_" " ")]))

(defn- address-render
  "Custom render function for address column"
  [address-name row]
  [:div
   [:div {:style {:font-weight "500" :color "#111827" :font-size "0.875rem"}}
    address-name]
   (when (:worksheet/address-city row)
     [:div {:style {:color "#6b7280" :font-size "0.75rem" :margin-top "0.25rem"}}
      (:worksheet/address-city row)])])

(defn- assigned-to-render
  "Custom render function for assigned to column"
  [assigned-to-name row]
  [:div
   (if assigned-to-name
     [:div {:style {:color "#374151" :font-size "0.875rem" :font-weight "500"}}
      assigned-to-name]
     [:span {:style {:color "#9ca3af" :font-style "italic" :font-size "0.75rem"}}
      "Unassigned"])])



;; Re-frame events and subscriptions
(rf/reg-sub
  :worksheets/data
  (fn [db _]
    (get-in db [:worksheets :data] {:worksheets [] :pagination {}})))

(rf/reg-sub
  :worksheets/loading?
  (fn [db _]
    (get-in db [:worksheets :loading?] false)))

(rf/reg-sub
  :worksheets/modal-worksheet
  (fn [db _]
    (get-in db [:worksheets :modal-worksheet] nil)))

(rf/reg-sub
  :worksheets/modal-is-new?
  (fn [db _]
    (get-in db [:worksheets :modal-is-new?] false)))

(rf/reg-sub
  :worksheets/authenticated?
  (fn [db _]
    (get-in db [:worksheets :authenticated?] nil)))

(rf/reg-event-db
  :worksheets/set-loading
  (fn [db [_ loading?]]
    (assoc-in db [:worksheets :loading?] loading?)))

(rf/reg-event-db
  :worksheets/set-data
  (fn [db [_ data]]
    (-> db
        (assoc-in [:worksheets :data] data)
        (assoc-in [:worksheets :loading?] false))))

(rf/reg-event-db
  :worksheets/set-modal-worksheet
  (fn [db [_ worksheet]]
    (assoc-in db [:worksheets :modal-worksheet] worksheet)))

(rf/reg-event-db
  :worksheets/set-modal-is-new
  (fn [db [_ is-new?]]
    (assoc-in db [:worksheets :modal-is-new?] is-new?)))

(rf/reg-event-db
  :worksheets/set-authenticated
  (fn [db [_ authenticated?]]
    (assoc-in db [:worksheets :authenticated?] authenticated?)))

(rf/reg-event-db
  :worksheets/close-modal
  (fn [db _]
    (assoc-in db [:worksheets :modal-worksheet] nil)))

;; Modal form state management
(rf/reg-sub
  :worksheets/modal-form-data
  (fn [db _]
    (get-in db [:worksheets :modal-form-data] {})))

(rf/reg-sub
  :worksheets/modal-form-errors
  (fn [db _]
    (get-in db [:worksheets :modal-form-errors] {})))

(rf/reg-sub
  :worksheets/modal-form-loading?
  (fn [db _]
    (get-in db [:worksheets :modal-form-loading?] false)))

(rf/reg-event-db
  :worksheets/set-modal-form-data
  (fn [db [_ data]]
    ;; Construct address object from address data
    (let [enhanced-data (if (and (:worksheet/address-id data) 
                                 (:worksheet/address-name data))
                          (assoc data :worksheet/address
                                 {:address/id (:worksheet/address-id data)
                                  :address/name (:worksheet/address-name data)
                                  :address/display (str (:worksheet/address-name data) 
                                                        " - " (:worksheet/address-city data))})
                          data)]
      (assoc-in db [:worksheets :modal-form-data] enhanced-data))))

(rf/reg-event-db
  :worksheets/update-modal-form-field
  (fn [db [_ field-key value]]
    (assoc-in db [:worksheets :modal-form-data field-key] value)))

(rf/reg-event-db
  :worksheets/set-modal-form-errors
  (fn [db [_ errors]]
    (assoc-in db [:worksheets :modal-form-errors] errors)))

(rf/reg-event-db
  :worksheets/set-modal-form-loading
  (fn [db [_ loading?]]
    (assoc-in db [:worksheets :modal-form-loading?] loading?)))

(rf/reg-event-db
  :worksheets/modal-form-set-field
  (fn [db [_ field-key value]]
    (assoc-in db [:worksheets :modal-form-data field-key] value)))

(rf/reg-sub
  :worksheets/modal-form-field
  (fn [db [_ field-key]]
    (get-in db [:worksheets :modal-form-data field-key] nil)))

(rf/reg-event-db
  :worksheets/clear-modal-form
  (fn [db _]
    (-> db
        (assoc-in [:worksheets :modal-form-data] {})
        (assoc-in [:worksheets :modal-form-errors] {})
        (assoc-in [:worksheets :modal-form-loading?] false)
        (assoc-in [:worksheets :maintainer-signature-ref] nil)
        (assoc-in [:worksheets :customer-signature-ref] nil)
        (assoc-in [:worksheets :signature-zoom-data] nil)
        (assoc-in [:worksheets :zoom-signature-ref] nil))))

;; Signature canvas ref management
(rf/reg-event-db
  :worksheets/set-maintainer-signature-ref
  (fn [db [_ ref]]
    (println "DEBUG set-maintainer-signature-ref:" ref)
    (assoc-in db [:worksheets :maintainer-signature-ref] ref)))

(rf/reg-event-db
  :worksheets/set-customer-signature-ref
  (fn [db [_ ref]]
    (println "DEBUG set-customer-signature-ref:" ref)
    (assoc-in db [:worksheets :customer-signature-ref] ref)))

(rf/reg-sub
  :worksheets/maintainer-signature-ref
  (fn [db _]
    (get-in db [:worksheets :maintainer-signature-ref])))

(rf/reg-sub
  :worksheets/customer-signature-ref
  (fn [db _]
    (get-in db [:worksheets :customer-signature-ref])))

;; Signature zoom overlay subscriptions and events
(rf/reg-sub
  :worksheets/signature-zoom-data
  (fn [db _]
    (get-in db [:worksheets :signature-zoom-data])))

(rf/reg-sub
  :worksheets/zoom-signature-ref
  (fn [db _]
    (get-in db [:worksheets :zoom-signature-ref])))

(rf/reg-event-db
  :worksheets/open-signature-zoom
  (fn [db [_ label]]
    (let [ref-dispatch-key (if (= label "Maintainer Signature")
                             :worksheets/set-maintainer-signature-ref
                             :worksheets/set-customer-signature-ref)]
      (assoc-in db [:worksheets :signature-zoom-data]
                {:label label
                 :ref-dispatch-key ref-dispatch-key}))))

(rf/reg-event-fx
  :worksheets/close-signature-zoom
  (fn [{:keys [db]} _]
    (let [zoom-data (get-in db [:worksheets :signature-zoom-data])
          zoom-ref (get-in db [:worksheets :zoom-signature-ref])]
      (println "DEBUG close-signature-zoom: zoom-data:" zoom-data)
      (println "DEBUG close-signature-zoom: zoom-ref:" zoom-ref)
      (println "DEBUG close-signature-zoom: zoom-ref isEmpty:" (when zoom-ref (.isEmpty zoom-ref)))
      ;; Store signature data directly in re-frame state
      (let [new-db (if (and zoom-data zoom-ref (not (.isEmpty zoom-ref)))
                     (let [signature-data (.toDataURL zoom-ref)
                           signature-key (if (= (:ref-dispatch-key zoom-data) :worksheets/set-maintainer-signature-ref)
                                           :worksheet/maintainer-signature
                                           :worksheet/customer-signature)]
                       (println "DEBUG close-signature-zoom: storing signature data, length:" (count signature-data))
                       (-> db
                           (assoc-in [:worksheets :modal-form-data signature-key] signature-data)
                           (assoc-in [:worksheets :signature-zoom-data] nil)
                           (assoc-in [:worksheets :zoom-signature-ref] nil)))
                     (-> db
                         (assoc-in [:worksheets :signature-zoom-data] nil)
                         (assoc-in [:worksheets :zoom-signature-ref] nil)))]
        {:db new-db}))))

(rf/reg-event-db
  :worksheets/set-zoom-signature-ref
  (fn [db [_ ref]]
    (assoc-in db [:worksheets :zoom-signature-ref] ref)))

;; Material templates subscription
(rf/reg-sub
  :material-templates/all
  (fn [db _]
    (get-in db [:material-templates :all] [])))

;; Material templates events
(rf/reg-event-db
  :material-templates/load
  (fn [db [_ workspace-id]]
    (when workspace-id
      (parquery/send-queries
       {:queries {:workspace-material-templates/get-all {:workspace-id workspace-id}}
        :parquery/context {:workspace-id workspace-id}
        :callback (fn [response]
                   (rf/dispatch [:material-templates/loaded (:workspace-material-templates/get-all response)]))}))
    db))

(rf/reg-event-db
  :material-templates/loaded
  (fn [db [_ templates]]
    (assoc-in db [:material-templates :all] (or templates []))))

;; Form field update event
(rf/reg-event-db
  :worksheets/update-form-field
  (fn [db [_ field-key value]]
    (assoc-in db [:worksheets :modal-form-data field-key] value)))

;; Material selection events
(rf/reg-event-db
  :worksheets/select-material-template
  (fn [db [_ template-id]]
    (assoc-in db [:worksheets :modal-form-data :worksheet/selected-material-template] template-id)))

(rf/reg-event-db
  :worksheets/add-selected-material
  (fn [db _]
    (let [form-data (get-in db [:worksheets :modal-form-data])
          selected-template-id (:worksheet/selected-material-template form-data)
          quantity (:worksheet/new-material-quantity form-data)
          templates (get-in db [:material-templates :all] [])]
      (if (and selected-template-id quantity (not (empty? (str quantity))))
        (let [selected-template (first (filter #(= (:material-template/id %) selected-template-id) templates))
              new-material {:name (:material-template/name selected-template)
                           :unit (:material-template/unit selected-template)
                           :quantity (str quantity)}
              current-materials (get form-data :worksheet/material-usage [])]
          (-> db
              (assoc-in [:worksheets :modal-form-data :worksheet/material-usage] 
                       (conj current-materials new-material))
              (assoc-in [:worksheets :modal-form-data :worksheet/selected-material-template] "")
              (assoc-in [:worksheets :modal-form-data :worksheet/new-material-quantity] "")))
        db))))

(rf/reg-event-db
  :worksheets/remove-material
  (fn [db [_ idx]]
    (let [current-materials (get-in db [:worksheets :modal-form-data :worksheet/material-usage] [])]
      (assoc-in db [:worksheets :modal-form-data :worksheet/material-usage]
               (vec (concat (take idx current-materials)
                           (drop (inc idx) current-materials)))))))

(rf/reg-event-db
  :worksheets/add-custom-material
  (fn [db _]
    (let [form-data (get-in db [:worksheets :modal-form-data])
          custom-name (:worksheet/custom-material-name form-data)
          custom-unit (:worksheet/custom-material-unit form-data)
          custom-quantity (:worksheet/custom-material-quantity form-data)]
      (if (and custom-name custom-unit custom-quantity 
               (not (empty? (str custom-name)))
               (not (empty? (str custom-unit)))
               (not (empty? (str custom-quantity))))
        (let [new-material {:name (str custom-name)
                           :unit (str custom-unit)
                           :quantity (str custom-quantity)}
              current-materials (get form-data :worksheet/material-usage [])]
          (-> db
              (assoc-in [:worksheets :modal-form-data :worksheet/material-usage] 
                       (conj current-materials new-material))
              (assoc-in [:worksheets :modal-form-data :worksheet/custom-material-name] "")
              (assoc-in [:worksheets :modal-form-data :worksheet/custom-material-unit] "")
              (assoc-in [:worksheets :modal-form-data :worksheet/custom-material-quantity] "")))
        db))))

;; REMOVED: Event to load worksheets - now using direct atom manipulation

;; Event to handle authentication check
(rf/reg-event-db
  :worksheets/check-authentication
  (fn [db _]
    (parquery/send-queries
     {:queries {:user/current {}}
      :parquery/context {}
      :callback (fn [response]
                 (let [user (:user/current response)]
                   (if (and user (:user/id user))
                     (do 
                       (rf/dispatch [:worksheets/set-authenticated true])
                       (when (empty? (:worksheets (get-in db [:worksheets :data]) []))
                         (rf/dispatch [:worksheets/load-data {}])))
                     (rf/dispatch [:worksheets/set-authenticated false]))))})
    db))

(defn view []
  (let [workspace-id (get-workspace-id)
        worksheets-data (r/atom [])
        loading? (r/atom false)
        modal-worksheet (r/atom nil)
        modal-is-new? (r/atom false)
        
        load-worksheets (fn [params]
                        (load-worksheets-query workspace-id params worksheets-data loading?))
        
        save-worksheet (fn [worksheet callback]
                       (save-worksheet-query worksheet workspace-id modal-is-new? callback modal-worksheet load-worksheets))
        
        delete-worksheet (fn [worksheet-id]
                         (delete-worksheet-query worksheet-id workspace-id (fn [] (load-worksheets {}))))]
    
    (fn []
      ;; Load initial data
      (when (empty? @worksheets-data)
        (load-worksheets {}))
      
      [:div {:style {:min-height "100vh" :background "#f9fafb"}}
     [:div {:style {:max-width "1200px" :margin "0 auto" :padding "2rem"}}
      ;; Page header with modal controls
        [page-header/page-header
         {:title (tr/tr :worksheets/page-title)
          :description (tr/tr :worksheets/page-description)
          :action-button [enhanced-button/enhanced-button
                          {:variant :success
                           :on-click (fn [] 
                                      (reset! modal-worksheet {:worksheet/status "draft"
                                                              :worksheet/work-type ""
                                                              :worksheet/service-type ""
                                                              :worksheet/creation-date (.toISOString (js/Date.))})
                                      (reset! modal-is-new? true))
                           :text (tr/tr :worksheets/add-new-worksheet)}]}]
        
        ;; Worksheets table 
      (str @worksheets-data)
      [data-table/server-side-data-table
       {:headers [{:key :worksheet/serial-number :label (tr/tr :worksheets/table-header-serial) :render worksheet-serial-render :sortable? true :style {:width "200px" :min-width "200px"} :cell-style {:width "200px" :min-width "200px"}}
                  {:key :worksheet/work-type :label (tr/tr :worksheets/table-header-work-type) :render work-type-render :sortable? true}
                  {:key :worksheet/address-name :label (tr/tr :worksheets/table-header-address) :render address-render :sortable? true}
                  {:key :worksheet/assigned-to-name :label (tr/tr :worksheets/table-header-assigned-to) :render assigned-to-render :sortable? true}]
        :data-source  @worksheets-data
        :loading? @loading?
        :empty-message (tr/tr :worksheets/no-worksheets-found)
        :id-key :worksheet/id
        :table-id :worksheets-table
        :show-search? true
        :show-pagination? true
        :query-fn load-worksheets
        :actions [{:key :edit :label (tr/tr :worksheets/action-edit) :variant :primary 
                   :on-click (fn [worksheet]
                               (println "Edit button clicked for worksheet:" (:worksheet/id worksheet))
                               (reset! modal-worksheet worksheet)
                               (reset! modal-is-new? false)
                               (println "Edit button atom reset completed"))}
                  {:key :pdf :label (tr/tr :worksheets/action-pdf) :variant :secondary
                   :on-click (fn [worksheet]
                               (let [pdf-url (str "/pdf-generator/worksheet/" (:worksheet/id worksheet))]
                                 (js/window.open pdf-url "_blank")))}
                  {:key :delete :label (tr/tr :worksheets/action-delete) :variant :danger 
                   :on-click (fn [row] 
                               (when (js/confirm (tr/tr :worksheets/confirm-delete))
                                 (delete-worksheet (:worksheet/id row))))}]}]
        
        ;; Modal when open
        (when @modal-worksheet
          [worksheet-modal @modal-worksheet @modal-is-new? save-worksheet
           (fn [] (reset! modal-worksheet nil))])]])))