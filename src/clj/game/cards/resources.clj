(ns game.cards.resources
  (:require [game.core :refer :all]
            [game.core.card :refer :all]
            [game.core.effects :refer [register-floating-effect]]
            [game.core.eid :refer [make-eid effect-completed]]
            [game.core.card-defs :refer [card-def]]
            [game.core.prompts :refer [show-wait-prompt clear-wait-prompt]]
            [game.core.toasts :refer [toast]]
            [game.utils :refer :all]
            [game.macros :refer [effect req msg wait-for continue-ability]]
            [clojure.string :refer [split-lines split join lower-case includes? starts-with?]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [jinteki.utils :refer :all]))

(defn- genetics-trigger?
  "Returns true if Genetics card should trigger - does not work with Adjusted Chronotype"
  [state side event]
  (or (first-event? state side event)
      (and (has-flag? state side :persistent :genetics-trigger-twice)
           (second-event? state side event))))

(defn- shard-constructor
  "Function for constructing a Shard card"
  [title target-server message effect-fn]
  {:events [{:event :successful-run
             :location :hand
             :req (req (and run (= (:server run) [target-server])))
             :optional
             {:prompt (str "Install " title "?")
              :yes-ability
              {:effect (effect
                         (add-run-effect
                           {:card card
                            :replace-access
                            {:mandatory true
                             :async true
                             :msg (str "install " title ", ignoring all costs")
                             :effect (effect (runner-install eid card {:ignore-all-cost true}))}}))}}}]
   :abilities [{:async true
                :cost [:trash]
                :msg message
                :effect (effect (effect-fn eid card target))}]})

(defn- trash-when-tagged-contructor
  "Constructor for a 'trash when tagged' card. Does not overwrite `:effect` key."
  [name definition]
  (let [trash-effect {:effect (req (when tagged
                                     (trash state :runner card {:unpreventable true})
                                     (system-msg state :runner (str "trashes " name " for being tagged"))))}
        definition (-> definition
                       (update :events #(apply conj % [(assoc trash-effect :event :runner-gain-tag)
                                                       (assoc trash-effect :event :runner-is-tagged)]))
                       (assoc :reactivate trash-effect))]
    ;; Add an effect only if there is none in the map
    (merge trash-effect definition)))

(defn companion-builder
  "Ability-req says when it can be used. turn-ends-effect defines what happens,
  and requires `effect-completed`."
  ([ability-req turn-ends-effect]
   (companion-builder
     ability-req
     turn-ends-effect
     {:msg "take 1 [Credits]"
      :effect (effect (add-counter card :credit -1)
                      (gain-credits 1))}))
  ([ability-req turn-ends-effect ability]
   (let [place-credit {:msg "add 1 [Credits] to itself"
                       :effect (effect (add-counter card :credit 1))}]
     {:events [(assoc place-credit :event :runner-turn-begins)
               (assoc place-credit :event :agenda-stolen)
               {:event :runner-turn-ends
                :req (req (<= 3 (get-counters (get-card state card) :credit)))
                :async true
                :effect turn-ends-effect}]
      :abilities [(assoc ability :req ability-req)]})))

(defn bitey-boi
  [f]
  (let [selector (resolve f)
        descriptor (str f)]
    {:abilities [{:req (req (and current-ice
                                 (rezzed? current-ice)
                                 (not (:broken (selector (:subroutines current-ice))))))
                  :cost [:trash]
                  :label (str "Break the " descriptor " subroutine")
                  :msg (msg "break the " descriptor " subroutine on " (:title current-ice)
                            " (\"[subroutine] " (:label (selector (:subroutines current-ice))) "\")")
                  :effect (req (break-subroutine! state current-ice (selector (:subroutines current-ice))))}]}))

;; Card definitions
(def card-definitions
  {"Aaron Marrón"
   (let [am {:effect (effect (add-counter card :power 2)
                             (system-msg :runner (str "places 2 power counters on Aaron Marrón")))}]
     {:abilities [{:cost [:power 1]
                   :msg "remove 1 tag and draw 1 card"
                   :async true
                   :effect (effect (lose-tags 1) (draw eid 1 nil))}]
      :events [(assoc am :event :agenda-scored)
               (assoc am :event :agenda-stolen)]})

   "Access to Globalsec"
   {:in-play [:link 1]}

   "Activist Support"
   {:events [{:event :corp-turn-begins
              :async true
              :effect (req (if (zero? (count-tags state))
                             (do (gain-tags state :runner eid 1)
                                 (system-msg state :runner (str "uses " (:title card) " to take 1 tag")))
                             (effect-completed state :runner eid)))}
             {:event :runner-turn-begins
              :async true
              :effect (req (if (not (has-bad-pub? state))
                             (do (gain-bad-publicity state :corp eid 1)
                                 (system-msg state :runner
                                             (str "uses " (:title card) " to give the corp 1 bad publicity")))
                             (effect-completed state :runner eid)))}]}

   "Adjusted Chronotype"
   {:events [{:event :runner-lose
              :req (req (and (some #{:click} target)
                             (let [click-losses (count (filter #(= :click %) (mapcat first (turn-events state side :runner-lose))))]
                               (or (= 1 click-losses)
                                   (and (= 2 click-losses)
                                        (has-flag? state side :persistent :genetics-trigger-twice))))))
              :msg "gain [Click]"
              :effect (effect (gain :runner :click 1))}]}

   "Aeneas Informant"
   {:events [{:event :no-trash
              :req (req (and (:trash target)
                             (not= (first (:zone target)) :discard)))
              :optional {:autoresolve (get-autoresolve :auto-reveal-and-gain)
                         :prompt "Use Aeneas Informant?"
                         :yes-ability {:msg (msg (str "gain 1 [Credits]"
                                                      (when-not (installed? target)
                                                        (str " and reveal " (:title target)))))
                                       :effect (effect (gain-credits 1))}}}]
    :abilities [(set-autoresolve :auto-reveal-and-gain "Aeneas Informant")]}

   "Aesop's Pawnshop"
   {:flags {:runner-phase-12 (req (>= (count (all-installed state :runner)) 2))}
    :abilities [{:effect (req (resolve-ability
                                state side
                                {:msg (msg "trash " (:title target) " and gain 3 [Credits]")
                                 :choices {:card #(and (runner? %)
                                                       (installed? %)
                                                       (not (same-card? % card)))}
                                 :effect (effect (gain-credits 3)
                                                 (trash target {:unpreventable true}))}
                                card nil))}]}

   "Akshara Sareen"
   {:in-play [:click-per-turn 1]
    :msg "give each player 1 additional [Click] to spend during their turn"
    :effect (effect (gain :corp :click-per-turn 1))
    :leave-play (effect (lose :corp :click-per-turn 1))}

   "Algo Trading"
   {:flags {:runner-phase-12 (req (pos? (:credit runner)))}
    :abilities [{:label "Move up to 3 [Credit] from credit pool to Algo Trading"
                 :prompt "Choose how many [Credit] to move" :once :per-turn
                 :choices {:number (req (min 3 (total-available-credits state :runner eid card)))}
                 :effect (effect (lose-credits target)
                                 (add-counter card :credit target))
                 :msg (msg "move " target " [Credit] to Algo Trading")}
                {:label "Take all credits from Algo Trading"
                 :cost [:click 1 :trash]
                 :msg (msg "trash it and gain " (get-counters card :credit) " [Credits]")
                 :effect (effect (gain-credits (get-counters card :credit)))}]
    :events [{:event :runner-turn-begins
              :req (req (>= (get-counters card :credit) 6))
              :effect (effect (add-counter card :credit 2)
                              (system-msg (str "adds 2 [Credit] to Algo Trading")))}]}

   "All-nighter"
   {:abilities [{:cost [:click 1 :trash]
                 :effect (effect (gain :click 2))
                 :msg "gain [Click][Click]"}]}

   "Always Be Running"
   {:implementation "Run requirement not enforced"
    :events [{:event :runner-turn-begins
              :effect (req (toast state :runner "Reminder: Always Be Running requires a run on the first click" "info"))}]
    :abilities [(assoc (break-sub [:click 2] 1 "All" {:req (req true)}) :once :per-turn)]}

   "Angel Arena"
   {:prompt "How many power counters?"
    :choices :credit
    :msg (msg "add " target " power counters")
    :effect (effect (add-counter card :power target))
    :abilities [{:cost [:power 1]
                 :msg "look at the top card of Stack"
                 :effect (req (when (not (pos? (get-counters (get-card state card) :power)))
                                (trash state :runner card {:unpreventable true})))
                 :optional {:prompt (msg "Add " (:title (first (:deck runner))) " to bottom of Stack?")
                            :yes-ability {:msg "add the top card of Stack to the bottom"
                                          :effect (req (move state side (first (:deck runner)) :deck))}}}]}

   "Armitage Codebusting"
   {:data {:counter {:credit 12}}
    :abilities [{:cost [:click 1]
                 :msg (msg "gain " (min 2 (get-counters card :credit)) " [Credits]")
                 :effect (req (let [credits (min 2 (get-counters card :credit))]
                                (add-counter state side card :credit (- credits))
                                (gain-credits state :runner credits))
                              (when (not (pos? (get-counters (get-card state card) :credit)))
                                (trash state :runner card {:unpreventable true})))}]}

   "Artist Colony"
   {:abilities [{:prompt "Choose a card to install"
                 :msg (msg "install " (:title target))
                 :req (req (not (install-locked? state side)))
                 :cost [:forfeit]
                 :choices (req (cancellable (filter #(not (event? %)) (:deck runner)) :sorted))
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (runner-install eid target nil))}]}

   "Assimilator"
   {:abilities [{:label "Turn a facedown card faceup"
                 :cost [:click 2]
                 :prompt "Select a facedown installed card"
                 :choices {:card #(and (facedown? %)
                                       (installed? %)
                                       (runner? %))}
                 :effect (req (if (or (event? target)
                                      (and (has-subtype? target "Console")
                                           (some #(has-subtype? % "Console") (all-active-installed state :runner))))
                                ;; Consoles and events are immediately unpreventably trashed.
                                (trash state side target {:unpreventable true})
                                ;; Other cards are moved to rig and have events wired.
                                (flip-faceup state side target)))
                 :msg (msg "turn " (:title target) " faceup")}]}

   "\"Baklan\" Bochkin"
   {:implementation "Encounter effect is manual."
    :abilities [{:label "Place 1 power counter"
                 :once :per-run
                 :msg (msg "places 1 power counter on " (:title card))
                 :effect (effect (add-counter card :power 1))}
                {:label "Derez a piece of ice currently being encountered"
                 :msg "derez a piece of ice currently being encountered and take 1 tag"
                 :req (req (and current-ice
                                (rezzed? current-ice)
                                (<= (get-strength current-ice) (get-counters (get-card state card) :power))))
                 :cost [:trash]
                 :effect (effect (derez current-ice)
                                 (gain-tags eid 1))}]}

   "Bank Job"
   {:data {:counter {:credit 8}}
    :events [(trash-on-empty :credit)
             {:event :successful-run
              :silent (req true)
              :req (req (is-remote? (:server run)))
              :effect (effect
                        (add-run-effect
                          {:card card
                           :replace-access
                           {:effect (req (letfn [(select-credits-ability [bj]
                                                   {:prompt "How many Bank Job credits?"
                                                    :choices {:number (req (get-counters (get-card state bj) :credit))}
                                                    :msg (msg "gain " target " [Credits]")
                                                    :effect (effect (gain-credits :runner target)
                                                                    (add-counter :runner bj :credit (- target)))})]
                                           (if (< 1 (count (filter #(= (:title %) "Bank Job") (all-active-installed state :runner))))
                                             (continue-ability
                                               state side
                                               {:prompt "Select a copy of Bank Job to use"
                                                :choices {:card #(and (installed? %)
                                                                      (= (:title %) "Bank Job"))}
                                                :effect (effect (continue-ability (select-credits-ability target) target nil))}
                                               card nil)
                                             (continue-ability state side (select-credits-ability card) card nil))))}}))}]}

   "Bazaar"
   (letfn [(hardware-and-in-hand? [target runner]
             (and (hardware? target)
                  (some #(= (:title %) (:title target)) (:hand runner))))]
     {:events [{:event :runner-install
                :interactive (req (hardware-and-in-hand? target runner))
                :silent (req (not (hardware-and-in-hand? target runner)))
                :async true
                :req (req (and (hardware? target) (= [:hand] (:previous-zone target))))
                :effect (effect (continue-ability
                                  (let [hw (:title target)]
                                    {:optional
                                     {:req (req (some #(when (= (:title %) hw) %) (:hand runner)))
                                      :prompt (msg "Install another copy of " hw "?")
                                      :msg (msg "install another copy of " hw)
                                      :yes-ability
                                      {:async true
                                       :effect (req (if-let [c (some #(when (= (:title %) hw) %) (:hand runner))]
                                                      (runner-install state side eid c nil)
                                                      (effect-completed state side eid)))}}})
                                  card nil))}]})

   "Beach Party"
   {:in-play [:hand-size 5]
    :events [{:event :runner-turn-begins
              :msg "lose [Click]"
              :effect (effect (lose :click 1))}]}

   "Beth Kilrain-Chang"
   (let [ability {:once :per-turn
                  :label "Gain 1 [Credits], draw 1 card, or gain [Click] (start of turn)"
                  :req (req (:runner-phase-12 @state))
                  :async true
                  :effect (req (let [c (:credit corp)
                                     b (:title card)]
                                 (cond
                                   ;; gain 1 credit
                                   (<= 5 c 9)
                                   (do (system-msg state side (str "uses " b " to gain 1 [Credits]"))
                                       (gain-credits state side 1)
                                       (effect-completed state side eid))
                                   ;; draw 1 card
                                   (<= 10 c 14)
                                   (do (system-msg state side (str "uses " b " to draw 1 card"))
                                       (draw state side eid 1 nil))
                                   ;; gain 1 click
                                   (<= 15 c)
                                   (do (system-msg state side (str "uses " b " to gain [Click]"))
                                       (gain state side :click 1)
                                       (effect-completed state side eid))
                                   :else (effect-completed state side eid))))}]
     {:flags {:drip-economy true}
      :abilities [ability]
      :events [(assoc ability :event :runner-turn-begins)]})

   "Bhagat"
   {:events [{:event :successful-run
              :req (req (and (= target :hq)
                             (first-successful-run-on-server? state :hq)))
              :msg "force the Corp to trash the top card of R&D"
              :effect (effect (mill :corp))}]}

   "Bio-Modeled Network"
   {:interactions {:prevent [{:type #{:net}
                              :req (req true)}]}
    :events [{:event :pre-damage
              :req (req (= target :net))
              :effect (effect (update! (assoc card :dmg-amount (nth targets 2))))}]
    :abilities [{:msg (msg "prevent " (dec (:dmg-amount card)) " net damage")
                 :cost [:trash]
                 :effect (effect (damage-prevent :net (dec (:dmg-amount card))))}]}

   "Biometric Spoofing"
   {:interactions {:prevent [{:type #{:net :brain :meat}
                              :req (req true)}]}
    :abilities [{:label "Prevent 2 damage"
                 :msg "prevent 2 damage"
                 :cost [:trash]
                 :effect (effect (damage-prevent :brain 2)
                                 (damage-prevent :net 2)
                                 (damage-prevent :meat 2))}]}

   "Blockade Runner"
   {:abilities [{:cost [:click 2]
                 :msg "draw 3 cards and shuffle 1 card from their Grip back into their Stack"
                 :async true
                 :effect (req (wait-for (draw state side 3 nil)
                                        (resolve-ability state side
                                                         {:prompt "Choose a card in your Grip to shuffle back into your Stack"
                                                          :choices {:card #(and (in-hand? %)
                                                                                (runner? %))}
                                                          :effect (effect (move target :deck)
                                                                          (shuffle! :deck))}
                                                         card nil)))}]}

   "Bloo Moose"
   {:flags {:runner-phase-12 (req true)}
    :abilities [{:req (req (and (:runner-phase-12 @state)
                                (not (seq (get-in @state [:runner :locked :discard])))))
                 :once :per-turn
                 :prompt "Choose a card in the Heap to remove from the game and gain 2 [Credits]"
                 :show-discard true
                 :choices {:card #(and (in-discard? %)
                                       (runner? %))}
                 :msg (msg "remove " (:title target) " from the game and gain 2 [Credits]")
                 :effect (effect (gain-credits 2)
                                 (move target :rfg))}]}

   "Borrowed Satellite"
   {:in-play [:hand-size 1 :link 1]}

   "Bug Out Bag"
   {:prompt "How many power counters?"
    :choices :credit
    :msg (msg "add " target " power counters")
    :effect (effect (add-counter card :power target))
    :events [{:event :runner-turn-ends
              :req (req (zero? (count (:hand runner))))
              :msg (msg "draw " (get-counters card :power) " cards. Bug Out Bag is trashed")
              :async true
              :effect (req (wait-for (draw state side (get-counters card :power) nil)
                                     (trash state side eid card nil)))}]}

   "Caldera"
   {:interactions {:prevent [{:type #{:net :brain}
                              :req (req true)}]}
    :abilities [{:cost [:credit 3]
                 :msg "prevent 1 net damage"
                 :effect (effect (damage-prevent :net 1))}
                {:cost [:credit 3]
                 :msg "prevent 1 brain damage"
                 :effect (effect (damage-prevent :brain 1))}]}

   "Charlatan"
   {:abilities [{:cost [:click 2]
                 :label "Make a run"
                 :prompt "Choose a server"
                 :choices (req runnable-servers)
                 :msg (msg "make a run on " target)
                 :effect (effect (make-run target nil card))}
                {:label "Pay credits equal to strength of approached rezzed ICE to bypass it"
                 :once :per-run
                 :req (req (and (:run @state) (rezzed? current-ice)))
                 :msg (msg "pay " (:current-strength current-ice) " [Credits] and bypass " (:title current-ice))
                 :effect (effect (pay :runner card :credit (:current-strength current-ice)))}]}

   "Chatterjee University"
   {:abilities [{:cost [:click 1]
                 :label "Place 1 power counter"
                 :msg "place 1 power counter on it"
                 :effect (effect (add-counter card :power 1))}
                {:cost [:click 1]
                 :label "Install a program from your Grip"
                 :prompt "Select a program to install from your Grip"
                 :choices
                 {:async true
                  :req (req (and (program? target)
                                 (in-hand? target)
                                 (can-pay? state :runner eid card nil
                                           [:credit (install-cost state side target
                                                                  {:cost-bonus (- (get-counters card :power))})])))}
                 :msg (msg "install " (:title target))
                 :effect (req (wait-for (runner-install state side target {:cost-bonus (- (get-counters card :power))})
                                        (when (pos? (get-counters card :power))
                                          (add-counter state side card :power -1))
                                        (effect-completed state side eid)))}]}

   "Chrome Parlor"
   {:events [{:event :pre-damage
              :req (req (has-subtype? (second targets) "Cybernetic"))
              :effect (effect (damage-prevent target Integer/MAX_VALUE))}]}

   "Citadel Sanctuary"
   {:interactions {:prevent [{:type #{:meat}
                              :req (req true)}]}
    :abilities [{:label "Prevent all meat damage"
                 :msg "prevent all meat damage"
                 :cost [:trash :trash-entire-hand]
                 :effect (effect (damage-prevent :meat Integer/MAX_VALUE))}]
    :events [{:event :runner-turn-ends
              :req (req (pos? (count-tags state)))
              :msg "force the Corp to initiate a trace"
              :trace {:base 1
                      :unsuccessful {:msg "remove 1 tag"
                                     :async true
                                     :effect (effect (lose-tags :runner eid 1))}}}]}

   "Clan Vengeance"
   {:events [{:event :pre-resolve-damage
              :req (req (pos? (last targets)))
              :effect (effect (add-counter card :power 1)
                              (system-msg :runner (str "places 1 power counter on Clan Vengeance")))}]
    :abilities [{:label "Trash 1 random card from HQ for each power counter"
                 :req (req (pos? (get-counters card :power)))
                 :msg (msg "trash " (min (get-counters card :power) (count (:hand corp))) " cards from HQ")
                 :cost [:trash]
                 :effect (effect (trash-cards (take (min (get-counters card :power) (count (:hand corp)))
                                                    (shuffle (:hand corp)))))}]}

   "Climactic Showdown"
   (letfn [(iced-servers [state]
             (filter #(-> (get-in @state (cons :corp (server->zone state %))) :ices count pos?) (zones->sorted-names (get-zones state))))
           (trash-or-bonus [chosen-server]
             {:player :corp
              :prompt "Choose a piece of ice to trash or cancel"
              :choices {:card #(and (= (last (:zone %)) :ices)
                                    (= chosen-server (rest (butlast (:zone %)))))}
              :effect (effect (system-msg (str "trashes " (card-str state target)))
                              (trash :corp eid target {:unpreventable true}))
              :cancel-effect (effect (system-msg (str "does not trash a piece of ice protecting " (zone->name chosen-server)))
                                     (register-events
                                       :runner card
                                       [{:event :pre-access
                                         :duration :until-runner-turn-ends
                                         :req (req (#{:hq :rd} target))
                                         :once :per-turn
                                         :msg (msg "access 2 additional cards from " (zone->name target))
                                         :effect (effect (access-bonus :runner target 2))}]))})]
     {:events [{:event :runner-turn-begins
                :async true
                :effect (req (let [card (move state side card :rfg)]
                               (continue-ability
                                 state side
                                 (if (pos? (count (iced-servers state)))
                                   {:prompt (msg  "Choose a server")
                                    :choices (req (iced-servers state))
                                    :msg (msg "choose " (zone->name (unknown->kw target))
                                              " and removes Climactic Showdown from the game")
                                    :effect (effect (continue-ability
                                                      :corp
                                                      (trash-or-bonus (rest (server->zone state target)))
                                                      card nil))}
                                   {:msg (str "choose a server protected by ice but cannot"
                                              " and removes Climactic Showdown from the game")})
                                 card nil)))}]})

   "Compromised Employee"
   {:recurring 1
    :events [{:event :rez
              :req (req (ice? target))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits :runner 1))}]
    :interactions {:pay-credits {:req (req (= :trace (:source-type eid)))
                                 :type :recurring}}}

   "Corporate Defector"
   {:events [{:event :corp-click-draw
              :msg (msg "reveal " (-> target first :title))
              :effect (effect (reveal target))}]}

   "Councilman"
   {:implementation "Does not restrict Runner to Asset / Upgrade just rezzed"
    :events [{:event :rez
              :req (req (and (#{"Asset" "Upgrade"} (:type target))
                             (can-pay? state :runner eid card nil [:credit (rez-cost state :corp target)])))
              :effect (req (toast state :runner (str "Click Councilman to derez " (card-str state target {:visible true})
                                                     " that was just rezzed") "info")
                           (toast state :corp (str "Runner has the opportunity to derez with Councilman.") "error"))}]
    :abilities [{:prompt "Select an asset or upgrade that was just rezzed"
                 :choices {:card #(and (rezzed? %)
                                       (or (asset? %)
                                           (upgrade? %)))}
                 :effect (effect
                           (continue-ability
                             :runner
                             (let [c target]
                               {:cost [:credit (rez-cost state :corp c)]
                                :msg (msg "derez " (:title c))
                                :effect (req (derez state :corp c)
                                             (register-turn-flag!
                                               state side card :can-rez
                                               (fn [state side card]
                                                 (if (same-card? card c)
                                                   ((constantly false)
                                                    (toast state :corp "Cannot rez the rest of this turn due to Councilman"))
                                                   true)))
                                             (trash state side card {:unpreventable true}))})
                             card nil))}]}

   "Counter Surveillance"
   {:implementation "Does not prevent access of cards installed in the root of a server"
    :abilities [{:cost [:click 1 :trash]
                 :makes-run true
                 :prompt "Choose a server to run with Counter Surveillance"
                 :msg (msg "run " target)
                 :choices (req (cancellable runnable-servers))
                 :effect (req (make-run state side target nil card)
                              (register-events
                                state side card
                                [{:event :successful-run
                                  :duration :end-of-run
                                  :silent (req true)
                                  :effect (req (let [tags (count-tags state)]
                                                 (if (<= tags (total-available-credits state :runner eid card))
                                                   ;; Can pay, do access
                                                   (continue-ability
                                                     state side
                                                     {:cost [:credit tags]
                                                      :msg (str "access up to " tags " cards")
                                                      :effect
                                                      (effect (access-bonus target (dec tags)))}
                                                     card targets)
                                                   ;; Can't pay, don't access cards
                                                   (do (system-msg state side "could not afford to use Counter Surveillance")
                                                       ;; Cannot access any cards
                                                       (max-access state side 0)))))}]))}]}

   "Crash Space"
   {:interactions {:prevent [{:type #{:meat}
                              :req (req true)}]
                   :pay-credits {:req (req (= :remove-tag (:source-type eid)))
                                 :type :recurring}}
    :recurring 2
    :abilities [{:label "Trash to prevent up to 3 meat damage"
                 :msg "prevent up to 3 meat damage"
                 :cost [:trash]
                 :effect (effect (damage-prevent :meat 3))}]}

   "Crowdfunding"
   (let [ability {:async true
                  :once :per-turn
                  :label "Take 1 [Credits] (start of turn)"
                  :msg "gain 1 [Credits]"
                  :req (req (:runner-phase-12 @state))
                  :effect (req (add-counter state side card :credit -1)
                               (gain-credits state :runner 1)
                               (if (not (pos? (get-counters (get-card state card) :credit)))
                                 (wait-for (trash state :runner card {:unpreventable true})
                                           (system-msg state :runner (str "trashes Crowdfunding"
                                                                          (when (not (empty? (:deck runner)))
                                                                            " and draws 1 card")))
                                           (draw state :runner eid 1 nil))
                                 (effect-completed state side eid)))}]
     {:data {:counter {:credit 3}}
      :flags {:drip-economy true
              :runner-turn-draw (req (= 1 (get-counters (get-card state card) :credit)))
              :runner-phase-12 (req (= 1 (get-counters (get-card state card) :credit)))}
      :abilities [ability]
      :events [(assoc ability :event :runner-turn-begins)
               {:event :runner-turn-ends
                :async true
                :location :discard
                :req (req (not (install-locked? state :runner)))
                :effect (effect
                          (continue-ability
                            {:optional
                             {:req (req (and (<= 3 (count (get-in @state [:runner :register :successful-run])))
                                             (not (get-in @state [:runner :register :crowdfunding-prompt]))))
                              :player :runner
                              :prompt "Install Crowdfunding?"
                              :yes-ability {:async true
                                            :effect (effect (runner-install :runner eid card {:ignore-all-cost true}))}
                              ;; Add a register to note that the player was already asked about installing,
                              ;; to prevent multiple copies from prompting multiple times.
                              :no-ability {:effect (req (swap! state assoc-in [:runner :register :crowdfunding-prompt] true))}}}
                            card nil))}]})

   "Crypt"
   {:events [{:event :successful-run
              :silent (req true)
              :req (req (= :archives target))
              :optional {:prompt "Place a virus counter on Crypt?"
                         :autoresolve (get-autoresolve :auto-add)
                         :yes-ability {:effect (effect (add-counter card :virus 1)
                                                       (system-msg "places a virus counter on Crypt"))}}}]
    :abilities [{:async true
                 :label "Install a virus program from the stack"
                 :prompt "Choose a virus"
                 :msg (msg "install " (:title target) " from the stack")
                 :choices (req (cancellable (filter #(and (program? %)
                                                          (has-subtype? % "Virus"))
                                                    (:deck runner)) :sorted))
                 :cost [:click 1 :virus 3 :trash]
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (runner-install eid target nil))}
                (set-autoresolve :auto-add "adding virus counters to Crypt")]}

   "Cybertrooper Talut"
   {:in-play [:link 1]
    :events [{:event :runner-install
              :silent (req true)
              :req (req (and (has-subtype? target "Icebreaker")
                             (not (has-subtype? target "AI"))))
              :effect (effect (pump target 2 :end-of-turn))}]}

   "Dadiana Chacon"
   (let [trashme {:effect (effect (unregister-events card)
                                  (damage eid :meat 3 {:unboostable true :card card}))
                  :cost [:trash]
                  :msg (msg "trashes Dadiana Chacon and suffers 3 meat damage")}
         ability {:once :per-turn
                  :msg "gain 1 [Credits]"
                  :req (req (< (get-in @state [:runner :credit]) 6))
                  :effect (req (gain-credits state :runner 1))}]
     {:effect (req (if (zero? (get-in @state [:runner :credit]))
                     (resolve-ability state side trashme card nil)
                     (add-watch state :dadiana
                                (fn [k ref old new]
                                  (when (and (not (zero? (get-in old [:runner :credit])))
                                             (zero? (get-in new [:runner :credit])))
                                    (resolve-ability ref side trashme card nil))))))
      :leave-play (req (remove-watch state :dadiana))
      :flags {:drip-economy true}
      :events [{:event :play}
               (assoc ability :event :runner-turn-begins)]})

   "Daily Casts"
   (let [ability {:once :per-turn
                  :label "Take 2 [Credits] (start of turn)"
                  :req (req (:runner-phase-12 @state))
                  :msg (msg "gain " (min 2 (get-counters card :credit)) " [Credits]")
                  :effect (req (let [credits (min 2 (get-counters card :credit))]
                                 (gain-credits state :runner credits)
                                 (add-counter state side card :credit (- credits))))}]
     {:data {:counter {:credit 8}}
      :flags {:drip-economy true}
      :abilities [ability]
      :events [(assoc ability :event :runner-turn-begins)
               (trash-on-empty :credit)]})

   "Data Dealer"
   {:abilities [{:cost [:click 1 :forfeit]
                 :effect (effect (gain-credits 9))
                 :msg (msg "gain 9 [Credits]")}]}

   "Data Folding"
   (let [ability {:label "Gain 1 [Credits] (start of turn)"
                  :msg "gain 1 [Credits]"
                  :once :per-turn
                  :req (req (and (>= (available-mu state) 2) (:runner-phase-12 @state)))
                  :effect (effect (gain-credits 1))}]
     {:flags {:drip-economy true}
      :abilities [ability]
      :events [(assoc ability :event :runner-turn-begins)]})

   "Data Leak Reversal"
   {:req (req (some #{:hq :rd :archives} (:successful-run runner-reg)))
    :abilities [{:req (req tagged)
                 :cost [:click 1]
                 :effect (effect (mill :corp))
                 :msg "force the Corp to trash the top card of R&D"}]}

   "DDoS"
   {:abilities [{:msg "prevent the corp from rezzing the outermost piece of ice during a run on any server this turn"
                 :cost [:trash]
                 :effect (effect
                           (register-turn-flag!
                             card :can-rez
                             (fn [state side card]
                               (let [idx (ice-index state card)]
                                 (if (and (ice? card)
                                          idx
                                          (= (count (get-in @state (concat [:corp :servers] (:server (:run @state)) [:ices])))
                                             (inc idx)))
                                   ((constantly false) (toast state :corp "Cannot rez any outermost ICE due to DDoS." "warning"))
                                   true)))))}]}

   "Dean Lister"
   {:abilities [{:req (req run)
                 :msg (msg "add +1 strength for each card in their Grip to " (:title target) " until the end of the run")
                 :choices {:card #(and (installed? %)
                                       (has-subtype? % "Icebreaker"))}
                 :cost [:trash]
                 :effect (effect (register-floating-effect
                                   card
                                   (let [breaker target]
                                     {:type :breaker-strength
                                      :duration :end-of-run
                                      :req (req (same-card? breaker target))
                                      :value (req (count (:hand runner)))}))
                                 (update-breaker-strength target))}]}

   "Decoy"
   {:interactions {:prevent [{:type #{:tag}
                              :req (req true)}]}
    :abilities [{:cost [:trash]
                 :msg "avoid 1 tag"
                 :effect (effect (tag-prevent :runner 1))}]}

   "District 99"
   (letfn [(eligible-cards [runner] (filter #(same-card? :faction (:identity runner) %)
                                            (:discard runner)))]
     {:implementation "Adding power counters must be done manually for programs/hardware trashed manually (e.g. by being over MU)"
      :abilities [{:label "Add a card from your heap to your grip"
                   :req (req (seq (eligible-cards runner)))
                   :cost [:click 1 :power 3]
                   :prompt "Select a card to add to grip?"
                   :choices (req (eligible-cards runner))
                   :effect (effect (move target :hand))
                   :msg (msg "add " (:title target) " to grip")}
                  {:label "Add a power counter manually"
                   :once :per-turn
                   :effect (effect (add-counter card :power 1))
                   :msg "manually add a power counter"}]
      :events (let [prog-or-hw #(or (program? (first %))
                                    (hardware? (first %)))
                    trash-event (fn [side-trash] {:event side-trash
                                                  :once :per-turn
                                                  :req (req (first-event? state side side-trash prog-or-hw))
                                                  :effect (effect (system-msg :runner "adds 1 power counter on District 99")
                                                                  (add-counter card :power 1))})]
                [(trash-event :corp-trash)
                 (trash-event :runner-trash)])})

   "DJ Fenris"
   (let [is-draft-id? #(starts-with? (:code %) "00")
         sorted-id-list (fn [runner] (->> (server-cards)
                                          (filter #(and (identity? %)
                                                        (has-subtype? % "g-mod")
                                                        (not= (-> runner :identity :faction)
                                                              (:faction %))
                                                        (not (is-draft-id? %))))
                                          (sort-by :title)))
         fenris-effect {:prompt "Choose a g-mod identity to host on DJ Fenris"
                        :choices (req (sorted-id-list runner))
                        :msg (msg "host " (:title target))
                        :effect (req (let [card (assoc-host-zones card)
                                           ;; Work around for get-card and update!
                                           c (assoc target :type "Fake-Identity")
                                           c (make-card c)
                                           c (assoc c
                                                    :host (dissoc card :hosted)
                                                    :zone '(:onhost)
                                                    ;; semi hack to get deactivate to work
                                                    :installed true)]
                                       ;; Manually host id on card
                                       (update! state side (assoc card :hosted [c]))
                                       (card-init state :runner c)
                                       ;; Clean-up
                                       (clear-wait-prompt state :corp)
                                       (effect-completed state side eid)))}]
     {:async true
      :effect (req (show-wait-prompt state :corp "Runner to pick identity to host on DJ Fenris")
                   (continue-ability state side fenris-effect card nil))
      ;; Handle Dr. Lovegood / Malia
      :disable {:effect (req (doseq [hosted (:hosted card)]
                               (disable-card state side hosted)))}
      :reactivate {:effect (req (doseq [hosted (:hosted card)
                                        :let [c (dissoc hosted :disabled)
                                              {:keys [effect events]} (card-def c)]]
                                  ;; Manually enable card to trigger `:effect`, similar to `enable-identity`
                                  (update! state side c)
                                  (when effect
                                    (effect state side (make-eid state) c nil))
                                  (register-events state side c)))}})

   "Donut Taganes"
   {:constant-effects [{:type :play-cost
                        :value 1}]}

   "Dr. Lovegood"
   {:flags {:runner-phase-12 (req (> (count (all-installed state :runner)) 1))}
    :abilities [{:req (req (:runner-phase-12 @state))
                 :prompt "Select an installed card to make its text box blank for the remainder of the turn"
                 :once :per-turn
                 :choices {:card installed?}
                 :msg (msg "make the text box of " (:title target) " blank for the remainder of the turn")
                 :effect (req (let [c target]
                                (disable-card state side c)
                                (register-events
                                  state side card
                                  [{:event :post-runner-turn-ends
                                    :effect (req (enable-card state side (get-card state c))
                                                 (when-let [reactivate-effect (:reactivate (card-def c))]
                                                   (resolve-ability state :runner reactivate-effect
                                                                    (get-card state c) nil))
                                                 (unregister-events state side card))}])))}]
    :events [{:event :post-runner-turn-ends}]}

   "Drug Dealer"
   {:flags {:runner-phase-12 (req (some #(card-flag? % :drip-economy true) (all-active-installed state :runner)))}
    :abilities [{:label "Lose 1 [Credits] (start of turn)"
                 :msg (msg (if (zero? (get-in @state [:runner :credit]))
                             "lose 0 [Credits] (runner has no credits to lose)"
                             "lose 1 [Credits]"))
                 :req (req (:runner-phase-12 @state))
                 :once :per-turn
                 :effect (effect (lose-credits 1))}]
    :events [{:event :corp-turn-begins
              :msg (msg "draw " (if (zero? (count (get-in @state [:runner :deck])))
                                  "0 cards (runner's stack is empty)"
                                  "1 card"))
              :async true
              :effect (effect (draw :runner eid 1 nil))}
             {:event :runner-turn-begins
              :msg (msg "lose " (if (zero? (get-in @state [:runner :credit]))
                                  "0 [Credits] (runner has no credits to lose)"
                                  "1 [Credits]"))
              :once :per-turn
              :effect (effect (lose-credits 1))}]}

   "Duggar's"
   {:abilities [{:cost [:click 4] :async true :effect (effect (draw eid 10 nil)) :msg "draw 10 cards"}]}

   "Dummy Box"
   (letfn [(dummy-prevent [card-type]
             {:msg (str "prevent a " card-type " from being trashed")
              :async true
              :cost [(keyword (str "trash-" card-type "-from-hand")) 1]
              :effect (effect (trash-prevent (keyword card-type) 1))})]
     {:interactions {:prevent [{:type #{:trash-hardware :trash-resource :trash-program}
                                :req (req (and (installed? (:prevent-target target))
                                               (not= :purge (:cause target))))}]}
      :abilities [(dummy-prevent "hardware")
                  (dummy-prevent "program")
                  (dummy-prevent "resource")]})

   "Earthrise Hotel"
   (let [ability {:msg "draw 2 cards"
                  :once :per-turn
                  :cost [:power 1]
                  :req (req (:runner-phase-12 @state))
                  :async true
                  :interactive (req true)
                  :effect (req (wait-for (draw state :runner 2 nil)
                                         (if (not (pos? (get-counters (get-card state card) :power)))
                                           (trash state :runner eid card {:unpreventable true})
                                           (effect-completed state side eid))))}]
     {:flags {:runner-turn-draw true
              :runner-phase-12 (req (< 1 (count (filter #(card-flag? % :runner-turn-draw true)
                                                        (cons (get-in @state [:runner :identity])
                                                              (all-active-installed state :runner))))))}
      :data {:counter {:power  3}}
      :events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Eden Shard"
   (shard-constructor "Eden Shard" :rd "force the Corp to draw 2 cards" (effect (draw :corp eid 2 nil)))

   "Emptied Mind"
   (let [ability {:req (req (zero? (count (:hand runner))))
                  :msg "gain [Click]"
                  :label "Gain [Click] (start of turn)"
                  :once :per-turn
                  :effect (effect (gain :click 1))}]
     {:events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Enhanced Vision"
   {:events [{:event :successful-run
              :silent (req true)
              :effect (req (let [card (first (shuffle (:hand corp)))]
                             (reveal state :corp card)
                             (system-msg state :runner "force the Corp to reveal " (:title card))))
              :req (req (genetics-trigger? state side :successful-run))}]}

   "Fall Guy"
   {:interactions {:prevent [{:type #{:trash-resource}
                              :req (req true)}]}
    :abilities [{:label "Prevent another installed resource from being trashed"
                 :cost [:trash]
                 :effect (effect (trash-prevent :resource 1))}
                {:label "Gain 2 [Credits]"
                 :msg "gain 2 [Credits]"
                 :cost [:trash]
                 :effect (effect (gain-credits 2))}]}

   "Fan Site"
   {:events [{:event :agenda-scored
              :msg "add it to their score area as an agenda worth 0 agenda points"
              :async true
              :req (req (installed? card))
              :effect (req (as-agenda state :runner eid card 0))}]}

   "Fencer Fueno"
   (assoc
     (companion-builder
       (req (and (pos? (get-counters (get-card state card) :credit))
                 (:successful run)))
       (effect (show-wait-prompt :corp "Runner to take decision on Fencer Fueno")
               (continue-ability
                 {:prompt "Pay 1 [Credits] or trash Fencer Fueno?"
                  :choices (req (if (can-pay? state :runner eid card nil :credit 1)
                                  ["Pay 1 [Credits]" "Trash"]
                                  ["Trash"]))
                  :player :runner
                  :effect (req (if (= target "Trash")
                                 (do
                                   (trash state :runner card)
                                   (system-msg state :runner "trashes Fencer Fueno"))
                                 (do
                                   (pay state :runner card :credit 1)
                                   (system-msg state :runner "pays 1 [Credits] to avoid trashing Fencer Fueno")))
                               (clear-wait-prompt state :corp))}
                 card nil))
       {:msg "take 1 [Credits]"
        :effect (effect (add-counter card :credit -1)
                        (gain-run-credits 1))})
     :interactions {:pay-credits {:req (req (:successful run))
                                  :type :credit}})


   "Fester"
   {:events [{:event :purge
              :msg "force the Corp to lose 2 [Credits] if able"
              :effect (effect (pay :corp card :credit 2))}]}

   "Film Critic"
   (letfn [(get-agenda [card] (first (filter #(= "Agenda" (:type %)) (:hosted card))))
           (host-agenda? [agenda]
             {:optional {:prompt (str "You access " (:title agenda) ". Host it on Film Critic?")
                         :yes-ability {:effect (req (host state side card (move state side agenda :play-area))
                                                    (update! state side (dissoc (get-card state agenda) :counter))
                                                    (access-end state side eid agenda)
                                                    (when-not (:run @state)
                                                      (swap! state dissoc :access)))
                                       :msg (msg "host " (:title agenda) " instead of accessing it")}}})]
     {:events [{:event :access
                :req (req (and (empty? (filter #(= "Agenda" (:type %)) (:hosted card)))
                               (agenda? target)))
                :interactive (req true)
                :async true
                :effect (effect (continue-ability (host-agenda? target) card nil))}]
      :abilities [{:cost [:click 2] :label "Add hosted agenda to your score area"
                   :req (req (get-agenda card))
                   :async true
                   :effect (req (let [c (get-agenda card)
                                      points (get-agenda-points state :runner c)]
                                  (as-agenda state :runner eid c points)))
                   :msg (msg (let [c (get-agenda card)]
                               (str "add " (:title c) " to their score area and gain "
                                    (quantify (get-agenda-points state :runner c) "agenda point"))))}]})

   "Find the Truth"
   {:events [{:event :post-runner-draw
              :msg (msg "reveal that they drew: "
                        (join ", " (map :title (get-in @state [:runner :register :most-recent-drawn]))))
              :effect (effect (reveal (get-in @state [:runner :register :most-recent-drawn])))}
             {:event :successful-run
              :interactive (get-autoresolve :auto-peek (complement never?))
              :silent (get-autoresolve :auto-peek never?)
              :optional {:req (req (and (first-event? state side :successful-run)
                                        (-> @state :corp :deck count pos?)))
                         :autoresolve (get-autoresolve :auto-peek)
                         :prompt "Use Find the Truth to look at the top card of R&D?"
                         :yes-ability {:prompt (req (->> corp :deck first :title (str "The top card of R&D is ")))
                                       :msg "look at the top card of R&D"
                                       :choices ["OK"]}}}]
    :abilities [(set-autoresolve :auto-peek "Find the Truth's peek at R&D ability")]}

   "First Responders"
   {:abilities [{:cost [:credit 2]
                 :req (req (some corp? (map second (turn-events state :runner :damage))))
                 :msg "draw 1 card"
                 :async true
                 :effect (effect (draw eid 1 nil))}]}

   "Gang Sign"
   {:events [{:event :agenda-scored
              :async true
              :interactive (req true)
              :msg (msg "access " (quantify (get-in @state [:runner :hq-access]) "card") " from HQ")
              :effect (req (wait-for
                             ;; manually trigger the pre-access event to alert Nerve Agent.
                             (trigger-event-sync state side :pre-access :hq)
                             (let [from-hq (access-count state side :hq-access)
                                   ;; access-helper-hq uses a set to keep track of which cards have already
                                   ;; been accessed. By adding HQ root's contents to this set, we make the runner
                                   ;; unable to access those cards, as Gang Sign intends.
                                   accessed-cards (set (get-in @state [:corp :servers :hq :content]))
                                   ability (access-helper-hq state from-hq accessed-cards)]
                               (continue-ability state :runner ability card nil))))}]}

   "Gbahali"
   (bitey-boi 'last)

   "Gene Conditioning Shoppe"
   {:msg "make Genetics trigger a second time each turn"
    :effect (effect (register-persistent-flag! card :genetics-trigger-twice (constantly true)))
    :leave-play (effect (clear-persistent-flag! card :genetics-trigger-twice))}

   "Ghost Runner"
   {:data {:counter {:credit 3}}
    :abilities [{:msg "gain 1 [Credits]"
                 :req (req (:run @state))
                 :async true
                 :effect (req (add-counter state side card :credit -1)
                              (gain-credits state side 1)
                              (trigger-event-sync state side eid :spent-stealth-credit card))}]
    :events [(trash-on-empty :credit)]
    :interactions {:pay-credits {:req (req run)
                                 :type :credit}}}

   "Globalsec Security Clearance"
   {:req (req (< 1 (:link runner)))
    :flags {:runner-phase-12 (req true)}
    :abilities [{:msg "lose [Click] and look at the top card of R&D"
                 :once :per-turn
                 :effect (effect (prompt! card (str "The top card of R&D is "
                                                    (:title (first (:deck corp)))) ["OK"] {}))}]
    :events [{:event :runner-turn-begins
              :req (req (get-in @state [:per-turn (:cid card)]))
              :effect (effect (lose :click 1))}]}

   "Grifter"
   {:events [{:event :runner-turn-ends
              :effect (req (let [ab (if (get-in @state [:runner :register :successful-run])
                                      {:effect (effect (gain-credits 1)) :msg "gain 1 [Credits]"}
                                      {:effect (effect (trash card)) :msg "trash Grifter"})]
                             (resolve-ability state side ab card targets)))}]}

   "Guru Davinder"
   {:flags {:cannot-pay-net true}
    :events [{:event :pre-damage
              :req (req (and (or (= target :meat) (= target :net))
                             (pos? (last targets))))
              :msg (msg "prevent all " (if (= target :meat) "meat" "net") " damage")
              :effect (req (damage-prevent state side :meat Integer/MAX_VALUE)
                           (damage-prevent state side :net Integer/MAX_VALUE)
                           (if (< (:credit runner) 4)
                             (trash state side card)
                             (resolve-ability
                               state :runner
                               {:optional
                                {:prompt "Pay 4 [Credits] to prevent trashing Guru Davinder?"
                                 :player :runner
                                 :yes-ability {:effect (effect (lose-credits :runner 4)
                                                               (system-msg (str "pays 4 [Credits] to prevent Guru Davinder "
                                                                                "from being trashed")))}
                                 :no-ability {:effect (effect (trash card))}}}
                               card nil)))}]}

   "Hades Shard"
   (shard-constructor "Hades Shard" :archives "access all cards in Archives"
                      (req (swap! state update-in [:corp :discard] #(map (fn [c] (assoc c :seen true)) %))
                           (wait-for (trigger-event-sync state side :pre-access :archives)
                                     (resolve-ability state :runner
                                                      (choose-access (get-in @state [:corp :discard])
                                                                     '(:archives) {:no-root true}) card nil))))

   "Hard at Work"
   (let [ability {:msg "gain 2 [Credits] and lose [Click]"
                  :once :per-turn
                  :effect (effect (lose :click 1) (gain-credits 2))}]
     {:flags {:drip-economy true}
      :events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Hernando Cortez"
   {:constant-effects [{:type :rez-additional-cost
                        :req (req (and (<= 10 (:credit corp))
                                       (ice? target)))
                        :value (req [:credit (count (:subroutines target))])}]}

   "Human First"
   {:events [{:event :agenda-scored
              :msg (msg "gain " (get-agenda-points state :corp target) " [Credits]")
              :effect (effect (gain-credits :runner (get-agenda-points state :corp target)))}
             {:event :agenda-stolen
              :msg (msg "gain " (get-agenda-points state :runner target) " [Credits]")
              :effect (effect (gain-credits (get-agenda-points state :runner target)))}]}

   "Hunting Grounds"
   {:abilities [{:label "Prevent a \"when encountered\" ability on a piece of ICE"
                 :msg "prevent a \"when encountered\" ability on a piece of ICE"
                 :once :per-turn}
                (letfn [(ri [cards]
                          {:async true
                           :effect (req (if (seq cards)
                                          (wait-for (runner-install state side (first cards) {:facedown true})
                                                    (continue-ability state side (ri (rest cards)) card nil))
                                          (effect-completed state side eid)))})]
                  {:async true
                   :label "Install the top 3 cards of your Stack facedown"
                   :msg "install the top 3 cards of their Stack facedown"
                   :cost [:trash]
                   :effect (effect (continue-ability (ri (take 3 (get-in @state [:runner :deck]))) card nil))})]}

   "Ice Analyzer"
   {:implementation "Credit use restriction is not enforced"
    :events [{:event :rez
              :req (req (ice? target))
              :msg "place 1 [Credits] on Ice Analyzer"
              :effect (effect (add-counter :runner card :credit 1))}]
    :abilities [{:effect (effect (add-counter card :credit -1)
                                 (gain-credits 1))
                 :msg "take 1 [Credits] to install programs"}]
    :interactions {:pay-credits {:req (req (and (= :runner-install (:source-type eid))
                                                (program? target)))
                                 :type :credit}}}

   "Ice Carver"
   {:constant-effects [{:type :ice-strength
                        :req (req (same-card? current-ice target))
                        :value -1}]}

   "Inside Man"
   {:recurring 2
    :interactions {:pay-credits {:req (req (and (= :runner-install (:source-type eid))
                                                (hardware? target)))
                                 :type :recurring}}}

   "Investigative Journalism"
   {:req (req (has-bad-pub? state))
    :abilities [{:cost [:click 4 :trash] :msg "give the Corp 1 bad publicity"
                 :effect (effect (gain-bad-publicity :corp 1))}]}

   "Jackpot!"
   (let [jackpot {:interactive (req true)
                  :async true
                  :req (req (= :runner (:as-agenda-side target)))
                  :effect (req (show-wait-prompt state :corp "Runner to use Jackpot!")
                               (continue-ability
                                 state side
                                 {:optional
                                  {:prompt "Trash Jackpot!?"
                                   :no-ability {:effect (effect (clear-wait-prompt :corp))}
                                   :yes-ability
                                   {:prompt "Choose how many [Credit] to take"
                                    :choices {:number (req (get-counters card :credit))}
                                    :async true
                                    :effect (req (gain-credits state :runner target)
                                                 (system-msg state :runner (str "trashes Jackpot! to gain " target " credits"))
                                                 (clear-wait-prompt state :corp)
                                                 (trash state :runner eid card nil))}}}
                                 card nil))}]
     {:events [{:event :runner-turn-begins
                :effect (effect (add-counter :runner card :credit 1))}
               (-> jackpot
                   (assoc :event :agenda-stolen)
                   (dissoc :req))
               (assoc jackpot :event :as-agenda)]})

   "Jak Sinclair"
   (let [ability {:label "Make a run (start of turn)"
                  :prompt "Choose a server to run with Jak Sinclair"
                  :once :per-turn
                  :req (req (:runner-phase-12 @state))
                  :choices (req runnable-servers)
                  :msg (msg "make a run on " target " during which no programs can be used")
                  :makes-run true
                  :effect (effect (make-run target nil card))}]
     {:implementation "Doesn't prevent program use"
      :flags {:runner-phase-12 (req true)}
      :install-cost-bonus (req (- (:link runner)))
      :events [{:event :runner-turn-begins
                :optional
                {:req (req (not (get-in @state [:per-turn (:cid card)])))
                 :prompt "Use Jak Sinclair to make a run?"
                 :yes-ability ability}}]
      :abilities [ability]})

   "Jarogniew Mercs"
   {:effect (effect (gain-tags :runner eid 1)
                    (add-counter card :power (+ 3 (count-tags state))))
    :flags {:untrashable-while-resources true}
    :interactions {:prevent [{:type #{:meat}
                              :req (req true)}]}
    :abilities [{:label "Prevent 1 meat damage"
                 :cost [:power 1]
                 :effect (req (damage-prevent state side :meat 1)
                              (when (not (pos? (get-counters (get-card state card) :power)))
                                (trash state :runner card {:unpreventable true})))}]}

   "John Masanori"
   {:events [{:event :successful-run
              :req (req (= 1 (count (get-in @state [:runner :register :successful-run]))))
              :interactive (req true)
              :msg "draw 1 card"
              :async true
              :effect (effect (draw eid 1 nil))}
             {:event :unsuccessful-run
              :req (req (= 1 (count (get-in @state [:runner :register :unsuccessful-run]))))
              :async true
              :msg "take 1 tag"
              :effect (effect (gain-tags :runner eid 1))}]}

   "Joshua B."
   (let [ability {:msg "gain [Click]"
                  :once :per-turn
                  :label "Gain [Click] (start of turn)"
                  :effect (effect (gain :click 1)
                                  (update! (assoc-in card [:special :joshua-b] true)))}]
     {:flags {:runner-phase-12 (req true)}
      :events [{:event :runner-turn-begins
                :optional {:prompt "Use Joshua B. to gain [Click]?"
                           :once :per-turn
                           :yes-ability ability}}
               {:event :runner-turn-ends
                :interactive (req true)
                :req (req (get-in card [:special :joshua-b]))
                :async true
                :effect (effect (gain-tags eid 1))
                :msg "gain 1 tag"}]
      :abilities [ability]})

   "Kasi String"
   {:events [{:event :run-ends
              :req (req (and (first-event? state :runner :run-ends is-remote?)
                             (not (get-in @state [:run :did-steal]))
                             (get-in @state [:run :did-access])
                             (is-remote? (:server run))))
              :effect (effect (add-counter card :power 1))
              :msg "add a power counter to itself"}
             {:event :counter-added
              :req (req (>= (get-counters (get-card state card) :power) 4))
              :effect (effect (as-agenda :runner card 1))
              :msg "add it to their score area as an agenda worth 1 agenda point"}]}

   "Kati Jones"
   {:abilities [{:cost [:click 1]
                 :msg "store 3 [Credits]"
                 :once :per-turn
                 :effect (effect (add-counter card :credit 3))}
                {:cost [:click 1]
                 :msg (msg "gain " (get-counters card :credit) " [Credits]")
                 :once :per-turn
                 :label "Take all credits"
                 :effect (req (gain-credits state side (get-counters card :credit))
                              (add-counter state side card :credit (- (get-counters card :credit))))}]}

   "Keros Mcintyre"
   {:events [{:event :derez
              :req (req (and (first-event? state side :derez)
                             (= (second targets) :runner)))
              :once :per-turn
              :msg "gain 2 [Credits]"
              :effect (effect (gain-credits 2))}]}

   "Kongamato"
   (bitey-boi 'first)

   "Laguna Velasco District"
   {:events [{:event :pre-runner-click-draw
              :msg "draw 1 additional card"
              :effect (effect (draw-bonus 1))}]}

   "Levy Advanced Research Lab"
   (letfn [(lab-keep [cards]
             {:prompt "Choose a Program to keep"
              :choices (cons "None" (filter #(= "Program" (:type %)) cards))
              :async true
              :msg (msg (if (= target "None") "take no card to their Grip" (str "take " (-> target :title) " to their Grip")))
              :effect (req (when (not= target "None")
                             (move state side target :hand))
                           (if (not-empty cards)
                             (let [tobottom (remove #(= % target) cards)]
                               (continue-ability state side (reorder-choice :runner :corp tobottom '()
                                                                            (count tobottom) tobottom "bottom") card nil))
                             (do (clear-wait-prompt state :corp)
                                 (effect-completed state side eid))))})]
     {:abilities [{:cost [:click 1]
                   :msg (msg "draw 4 cards: " (join ", " (map :title (take 4 (:deck runner)))))
                   :async true
                   :effect (req (show-wait-prompt state :corp "Runner to choose card to keep")
                                (let [from (take 4 (:deck runner))]
                                  (continue-ability state side (lab-keep from) card nil)))}]})

   "Lewi Guilherme"
   (let [ability {:once :per-turn
                  :optional {:once :per-turn
                             :prompt "Pay 1 [Credits] to keep Lewi Guilherme?"
                             :yes-ability {:effect (req (if (pos? (:credit runner))
                                                          (do (lose-credits state side 1)
                                                              (system-msg state side "pays 1 [Credits] to keep Lewi Guilherme"))
                                                          (do (trash state side card)
                                                              (system-msg state side "must trash Lewi Guilherme"))))}
                             :no-ability {:effect (effect (trash card)
                                                          (system-msg "chooses to trash Lewi Guilherme"))}}}]
     {:flags {:drip-economy true ;; for Drug Dealer
              :runner-phase-12 (req (< 1 (count (filter #(card-flag? % :drip-economy true)
                                                        (all-active-installed state :runner)))))}
      ;; KNOWN ISSUE: :effect is not fired when Assimilator turns cards over or Dr. Lovegood re-enables it.
      :effect (effect (lose :corp :hand-size 1))
      :leave-play (effect (gain :corp :hand-size 1))
      :abilities [(assoc ability :req (req (:runner-phase-12 @state)))]
      :events [(assoc ability :event :runner-turn-begins)]})

   "Liberated Account"
   {:data {:counter {:credit 16}}
    :abilities [{:cost [:click 1]
                 :msg (msg "gain " (min 4 (get-counters card :credit)) " [Credits]")
                 :effect (effect (gain-credits (min 4 (get-counters card :credit)))
                                 (add-counter card :credit (- (min 4 (get-counters card :credit)))))}]
    :events [(trash-on-empty :credit)]}

   "Liberated Chela"
   {:abilities [{:cost [:click 5 :forfeit]
                 :msg "add it to their score area"
                 :async true
                 :effect (req (if (not (empty? (:scored corp)))
                                (do (show-wait-prompt state :runner "Corp to decide whether or not to prevent Liberated Chela")
                                    (continue-ability
                                      state side
                                      {:prompt (msg "Forfeit an agenda to prevent Liberated Chela from being added to Runner's score area?")
                                       :choices ["Yes" "No"]
                                       :player :corp
                                       :async true
                                       :effect (effect (continue-ability
                                                         (if (= target "Yes")
                                                           {:player :corp
                                                            :prompt "Select an agenda to forfeit"
                                                            :choices {:card #(in-corp-scored? state side %)}
                                                            :effect (effect (forfeit target)
                                                                            (move :runner card :rfg)
                                                                            (clear-wait-prompt :runner))}
                                                           {:async true
                                                            :effect (req (clear-wait-prompt state :runner)
                                                                         (as-agenda state :runner eid card 2))
                                                            :msg "add it to their score area as an agenda worth 2 points"})
                                                         card nil))} card nil))
                                (continue-ability
                                  state side
                                  {:async true
                                   :effect (req (as-agenda state :runner eid card 2))
                                   :msg "add it to their score area as an agenda worth 2 points"} card nil)))}]}

   "Logic Bomb"
   {:implementation "Bypass effect is manual"
    :abilities [{:label "Bypass the encountered ice"
                 :req (req (and (:run @state)
                                (rezzed? current-ice)))
                 :msg (msg "bypass "
                           (:title current-ice)
                           (when (pos? (:click runner))
                             (str " and loses "
                                  (apply str (repeat (:click runner) "[Click]")))))
                 :cost [:trash]
                 :effect (effect (lose :click (:click runner)))}]}

   "London Library"
   {:abilities [{:async true
                 :label "Install a non-virus program on London Library"
                 :cost [:click 1]
                 :prompt "Select a non-virus program to install on London Library from your grip"
                 :choices {:card #(and (program? %)
                                       (not (has-subtype? % "Virus"))
                                       (in-hand? %))}
                 :msg (msg "host " (:title target))
                 :effect (effect (runner-install eid target {:host-card card :ignore-install-cost true}))}
                {:label "Add a program hosted on London Library to your Grip"
                 :cost [:click 1]
                 :choices {:card #(:host %)} ;TODO: this seems to allow all hosted cards to be bounced
                 :msg (msg "add " (:title target) " to their Grip")
                 :effect (effect (move target :hand))}]
    :events [{:event :runner-turn-ends
              :interactive (req true)
              :async true
              :effect (effect (trash-cards eid (filter program? (:hosted card)) nil))}]}

   "Maxwell James"
   {:in-play [:link 1]
    :abilities [{:req (req (some #{:hq} (:successful-run runner-reg)))
                 :prompt "Choose a piece of ICE protecting a remote server"
                 :choices {:card #(and (ice? %)
                                       (rezzed? %)
                                       (is-remote? (second (:zone %))))}
                 :msg "derez a piece of ICE protecting a remote server"
                 :cost [:trash]
                 :effect (effect (derez target))}]}

   "Miss Bones"
   {:data {:counter {:credit 12}}
    :interactions {:pay-credits {:req (req (and (= :runner-trash-corp-cards (:source-type eid))
                                                (installed? target)))
                                 :type :credit}}
    :abilities [{:prompt "Choose how many credits to take from Miss Bones"
                 :choices {:number (req (get-counters card :credit))}
                 :msg (msg "gain " target " [Credits] for trashing installed cards")
                 :effect (effect (gain-credits target)
                                 (add-counter card :credit (* -1 target)))}]
    :events [(trash-on-empty :credit)]}

   "Motivation"
   (let [ability {:msg "look at the top card of their Stack"
                  :label "Look at the top card of Stack (start of turn)"
                  :once :per-turn
                  :req (req (:runner-phase-12 @state))
                  :effect (effect (prompt! card (str "The top card of your Stack is "
                                                     (:title (first (:deck runner)))) ["OK"] {}))}]
     {:flags {:runner-turn-draw true
              :runner-phase-12 (req (some #(card-flag? % :runner-turn-draw true) (all-active-installed state :runner)))}
      :events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Mr. Li"
   {:abilities [{:cost [:click 1]
                 :msg (msg "draw 2 cards")
                 :async true
                 :effect (req (wait-for (draw state side 2 nil)
                                        (continue-ability
                                          state side
                                          (if-let [drawn (get-in @state [:runner :register :most-recent-drawn])]
                                            {:prompt "Select 1 card to add to the bottom of the Stack"
                                             :choices {:card #(and (in-hand? %)
                                                                   (some (fn [c] (same-card? c %)) drawn))}
                                             :msg (msg "add 1 card to the bottom of the Stack")
                                             :effect (req (move state side target :deck))})
                                          card nil)))}]}

   "Muertos Gang Member"
   {:effect (effect (continue-ability
                      :corp
                      {:prompt "Select a card to derez"
                       :choices {:card #(and (corp? %)
                                             (not (agenda? %))
                                             (:rezzed %))}
                       :effect (effect (derez target))}
                      card nil))
    :leave-play (effect (continue-ability
                          :corp
                          {:prompt "Select a card to rez, ignoring the rez cost"
                           :choices {:card (complement rezzed?)}
                           :effect (effect (rez target {:ignore-cost :rez-cost :no-msg true})
                                           (system-say (str (:title card) " allows the Corp to rez " (:title target) " at no cost")))}
                          card nil))
    :abilities [{:msg "draw 1 card"
                 :async true
                 :cost [:trash]
                 :effect (effect (draw eid 1 nil))}]}

   "Mystic Maemi"
   (assoc
     (companion-builder
       ;; companion-builder: ability-req
       (req (and (pos? (get-counters (get-card state card) :credit))
                 (:successful run)))
       ;; companion-builder: turn-ends-effect
       (effect (show-wait-prompt :corp "Runner to take decision on Mystic Maemi")
               (continue-ability
                 {:prompt "Trash 1 card from your grip at random or trash Mystic Maemi?"
                  :choices (req (if (can-pay? state :runner eid card nil :randomly-trash-from-hand 1)
                                  ["Card from grip" "Trash"]
                                  ["Trash"]))
                  :player :runner
                  :effect (req (clear-wait-prompt state :corp)
                               (if (= target "Trash")
                                 (do (trash state :runner card)
                                     (system-msg state :runner "trashes Mystic Maemi"))
                                 (wait-for (pay-sync state :runner (make-eid state {:source card :source-type :ability})
                                                     card [:randomly-trash-from-hand 1])
                                           (system-msg state :runner (str (build-spend-msg async-result "avoid" "trashing Mystic Maemi"))))))}
                 card nil))
       ;; companion-builder: ability
       {:msg "take 1 [Credits]"
        :effect (effect (add-counter card :credit -1)
                        (gain-run-credits 1))})
     ;; assoc: arguments
     :interactions {:pay-credits {:req (req (= :play (:source-type eid)))
                                  :type :credit}})

   "Net Mercur"
   {:abilities [{:msg "gain 1 [Credits]"
                 :async true
                 :effect (effect (add-counter card :credit -1)
                                 (gain-credits 1)
                                 (trigger-event-sync eid :spent-stealth-credit card))}]
    :events [{:event :spent-stealth-credit
              :req (req (and (:run @state)
                             (has-subtype? target "Stealth")))
              :once :per-run
              :async true
              :effect (effect (show-wait-prompt :corp "Runner to use Net Mercur")
                              (continue-ability
                                {:prompt "Place 1 [Credits] on Net Mercur or draw 1 card?"
                                 :player :runner
                                 :choices ["Place 1 [Credits]" "Draw 1 card"]
                                 :async true
                                 :effect (req (if (= target "Draw 1 card")
                                                (do (clear-wait-prompt state :corp)
                                                    (system-msg state :runner (str "uses Net Mercur to draw 1 card"))
                                                    (draw state side eid 1 nil))
                                                (do (clear-wait-prompt state :corp)
                                                    (system-msg state :runner (str "places 1 [Credits] on Net Mercur"))
                                                    (add-counter state :runner card :credit 1)
                                                    (effect-completed state side eid))))}
                                card nil))}]
    ; Normally this should be (req true), but having pay-credits prompts on
    ; literally every interaction would get tiresome. Therefore Net Mercur will
    ; only ask for payments during a run, traces, psi games, and prevention abilities
    :interactions {:pay-credits {:req (req (or run
                                               (#{:psi :trace} (:source-type eid))
                                               (#{:net :meat :brain :tag} (get-in @state [:prevent :current]))))
                                 :type :credit}}}

   "Network Exchange"
   {:msg "increase the install cost of non-innermost ICE by 1"
    :constant-effects [{:type :install-cost
                        :req (req (ice? target))
                        :value (req (when (pos? (count (:dest-zone (second targets)))) 1))}]}

   "Neutralize All Threats"
   {:in-play [:hq-access 1]
    :events [{:event :pre-access
              :req (req (and (= target :archives)
                             (seq (filter :trash (:discard corp)))))
              :effect (req (swap! state assoc-in [:per-turn (:cid card)] true))}
             {:event :pre-trash
              :req (req (let [cards (map first (rest (turn-events state side :pre-trash)))]
                          (and (empty? (filter :trash cards))
                               (number? (:trash target)))))
              :once :per-turn
              :msg (msg "reveal " (card-str state target {:visible true}))
              :effect (req (reveal state side target)
                           (swap! state assoc-in [:runner :register :must-trash-with-credits] true))}
             {:event :post-access-card
              :req (req (get-in @state [:runner :register :must-trash-with-credits]))
              :effect (req (swap! state assoc-in [:runner :register :must-trash-with-credits] false))}]}

   "New Angeles City Hall"
   {:interactions {:prevent [{:type #{:tag}
                              :req (req true)}]}
    :events [{:event :agenda-stolen
              :msg "trash itself"
              :effect (effect (trash card))}]
    :abilities [{:cost [:credit 2]
                 :msg "avoid 1 tag"
                 :effect (effect (tag-prevent :runner 1))}]}

   "No One Home"
   (letfn [(first-chance? [state side]
             (< (+ (event-count state side :pre-tag)
                   (event-count state side :pre-damage))
                2))
           (start-trace [type]
             (let [message (str "avoid any " (if (= type :net)
                                               "amount of net damage"
                                               "number of tags"))]
               {:player :corp
                :label (str "Trace 0 - if unsuccessful, " message)
                :trace {:base 0
                        :priority 11
                        :unsuccessful {:msg message
                                       :effect (req (if (= type :net)
                                                      (damage-prevent state side :net Integer/MAX_VALUE)
                                                      (tag-prevent state :runner Integer/MAX_VALUE)))}}}))]
     {:interactions {:prevent [{:type #{:net :tag}
                                :req (req (first-chance? state side))}]}
      :abilities [{:msg "force the Corp to trace"
                   :async true
                   :effect (req (let [type (get-in @state [:prevent :current])]
                                  (wait-for (trash state side card {:unpreventable true})
                                            (continue-ability state side (start-trace type)
                                                              card nil))))}]})

   "Off-Campus Apartment"
   {:flags {:runner-install-draw true}
    :abilities [{:async true
                 :label "Install and host a connection on Off-Campus Apartment"
                 :cost [:click 1]
                 :prompt "Select a connection in your Grip to install on Off-Campus Apartment"
                 :choices {:req (req (and (has-subtype? target "Connection")
                                          (can-pay? state side eid card nil [:credit (install-cost state side target)])
                                          (in-hand? target)))}
                 :msg (msg "host " (:title target) " and draw 1 card")
                 :effect (effect (runner-install eid target {:host-card card}))}
                {:label "Host an installed connection"
                 :prompt "Select a connection to host on Off-Campus Apartment"
                 :choices {:card #(and (has-subtype? % "Connection")
                                       (installed? %))}
                 :msg (msg "host " (:title target) " and draw 1 card")
                 :async true
                 :effect (effect (host card target)
                                 (draw eid 1 nil))}]
    :events [{:event :runner-install
              :req (req (same-card? card (:host target)))
              :async true
              :effect (effect (draw eid 1 nil))}]}

   "Officer Frank"
   {:abilities [{:cost [:credit 1 :trash]
                 :req (req (some #(= :meat %) (map first (turn-events state :runner :damage))))
                 :msg "force the Corp to trash 2 random cards from HQ"
                 :effect (effect (trash-cards :corp (take 2 (shuffle (:hand corp)))))}]}

   "Oracle May"
   {:abilities [{:cost [:click 1]
                 :once :per-turn
                 :prompt "Choose card type"
                 :choices ["Event" "Hardware" "Program" "Resource"]
                 :async true
                 :effect (req (let [c (first (get-in @state [:runner :deck]))]
                                (system-msg state side (str "spends [Click] to use Oracle May, names " target
                                                            " and reveals " (:title c)))
                                (reveal state side c)
                                (if (is-type? c target)
                                  (do (system-msg state side (str "gains 2 [Credits] and draws " (:title c)))
                                      (gain-credits state side 2)
                                      (draw state side eid 1 nil))
                                  (do (system-msg state side (str "trashes " (:title c)))
                                      (mill state side)
                                      (effect-completed state side eid)))))}]}

   "Order of Sol"
   {:effect (req (add-watch state :order-of-sol
                            (fn [k ref old new]
                              (when (and (not (zero? (get-in old [:runner :credit])))
                                         (zero? (get-in new [:runner :credit])))
                                (resolve-ability ref side {:msg "gain 1 [Credits]"
                                                           :once :per-turn
                                                           :effect (effect (gain-credits 1))}
                                                 card nil)))))
    :events [{:event :runner-turn-begins
              :req (req (zero? (:credit runner)))
              :msg "gain 1 [Credits]"
              :effect (req (gain-credits state :runner 1)
                           (swap! state assoc-in [:per-turn (:cid card)] true))}
             {:event :corp-turn-begins
              :req (req (zero? (:credit runner)))
              :msg "gain 1 [Credits]"
              :effect (req (gain-credits state :runner 1)
                           (swap! state assoc-in [:per-turn (:cid card)] true))}
             {:event :runner-install
              :silent (req (pos? (:credit runner)))
              :req (req (and (= target card)
                             (zero? (:credit runner))))
              :msg "gain 1 [Credits]"
              :effect (req (gain-credits state :runner 1)
                           (swap! state assoc-in [:per-turn (:cid card)] true))}]
    :leave-play (req (remove-watch state :order-of-sol))}

   "PAD Tap"
   {:events [{:event :corp-credit-gain
              :req (req (and (not= target :corp-click-credit)
                             (= 1 (->> (turn-events state :corp :corp-credit-gain)
                                       (remove #(= (first %) :corp-click-credit))
                                       count))))
              :once :per-turn
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits :runner 1))}]
    :corp-abilities [{:label "Trash PAD Tap"
                      :cost [:click 1 :credit 3]
                      :req (req (= :corp side))
                      :effect (effect (system-msg :corp "spends [Click] and 3 [Credits] to trash PAD Tap")
                                      (trash :corp card))}]}

   "Paige Piper"
   (letfn [(pphelper [title cards]
             {:optional
              {:prompt (str "Use Paige Piper to trash copies of " title "?")
               :yes-ability {:prompt "How many would you like to trash?"
                             :choices (take (inc (count cards)) ["0" "1" "2" "3" "4" "5"])
                             :msg "shuffle their Stack"
                             :effect (req (let [target (str->int target)]
                                            (trigger-event state side :searched-stack nil)
                                            (shuffle! state :runner :deck)
                                            (doseq [c (take target cards)]
                                              (trash state side c {:unpreventable true}))
                                            (when (pos? target)
                                              (system-msg state side (str "trashes "
                                                                          (quantify target "cop" "y" "ies")
                                                                          " of " title)))))}}})]
     {:events [{:event :runner-install
                :req (req (first-event? state side :runner-install))
                :async true
                :effect (effect (continue-ability
                                  (pphelper (:title target)
                                            (->> (:deck runner)
                                                 (filter #(= (:title %) (:title target)))
                                                 (vec)))
                                  card nil))}]})

   "Paparazzi"
   {:effect (req (swap! state update-in [:runner :tag :is-tagged] inc)
                 (trigger-event state :runner :runner-is-tagged true))
    :events [{:event :pre-damage
              :req (req (= target :meat)) :msg "prevent all meat damage"
              :effect (effect (damage-prevent :meat Integer/MAX_VALUE))}]
    :leave-play (req (swap! state update-in [:runner :tag :is-tagged] dec)
                     (trigger-event state :runner :runner-is-tagged (pos? (get-in @state [:runner :tag :is-tagged]))))}

   "Patron"
   (let [ability {:prompt "Choose a server for Patron"
                  :choices (req (conj servers "No server"))
                  :req (req (and (:runner-phase-12 @state)
                                 (not (used-this-turn? (:cid card) state))))
                  :msg (msg "target " target)
                  :effect (req (when (not= target "No server")
                                 (update! state side (assoc card :server-target target))))}]
     {:events [(assoc ability :event :runner-turn-begins)
               {:event :successful-run
                :req (req (= (zone->name (get-in @state [:run :server])) (:server-target (get-card state card))))
                :once :per-turn
                :effect (effect (add-run-effect
                                  {:card card
                                   :replace-access
                                   {:mandatory true
                                    :msg "draw 2 cards instead of accessing"
                                    :async true
                                    :effect (effect (update! (dissoc (get-card state card) :server-target))
                                                    (draw eid 2 nil))}}))}
               {:event :runner-turn-ends
                :effect (effect (update! (dissoc (get-card state card) :server-target)))}]
      :abilities [ability]})

   "Personal Workshop"
   (let [remove-counter
         {:async true
          :req (req (not (empty? (:hosted card))))
          :once :per-turn
          :msg (msg "remove 1 counter from " (:title target))
          :choices {:card #(:host %)}
          :effect (req (if (not (pos? (get-counters (get-card state target) :power)))
                         (runner-install state side eid (dissoc target :counter) {:ignore-all-cost true})
                         (do (add-counter state side target :power -1)
                             (effect-completed state side eid))))}]
     {:flags {:drip-economy true}
      :abilities [{:async true
                   :label "Host a program or piece of hardware"
                   :cost [:click 1]
                   :prompt "Select a card to host on Personal Workshop"
                   :choices {:card #(and (or (program? %)
                                             (hardware? %))
                                         (in-hand? %)
                                         (runner? %))}
                   :effect (req (if (not (pos? (:cost target)))
                                  (runner-install state side (assoc eid :source card :source-type :runner-install) target nil)
                                  (do (host state side card
                                            (assoc target :counter {:power (:cost target)}))
                                      (effect-completed state side eid))))
                   :msg (msg "host " (:title target) "")}
                  (assoc remove-counter
                         :label "Remove 1 counter from a hosted card (start of turn)"
                         :cost [:credit 1])
                  {:async true
                   :label "X[Credit]: Remove counters from a hosted card"
                   :choices {:card #(:host %)}
                   :req (req (not (empty? (:hosted card))))
                   :effect (effect
                             (continue-ability
                               (let [paydowntarget target
                                     num-counters (get-counters (get-card state paydowntarget) :power)]
                                 {:async true
                                  :prompt "How many counters to remove?"
                                  :choices {:number (req (min num-counters
                                                              (total-available-credits state :runner eid card)))}
                                  :effect (req (wait-for
                                                 (pay-sync state :runner card [:credit target])
                                                 (if-let [cost-str async-result]
                                                   (do (system-msg state side
                                                                   (str (build-spend-msg cost-str "use") (:title card)
                                                                        " to remove " target
                                                                        " counters from " (:title paydowntarget)))
                                                       (if (= num-counters target)
                                                         (runner-install state side (assoc eid :source card :source-type :runner-install) (dissoc paydowntarget :counter) {:ignore-all-cost true})
                                                         (do (add-counter state side paydowntarget :power (- target))
                                                             (effect-completed state side eid))))
                                                   (effect-completed state side eid))))})
                               card nil))}]
      :events [(assoc remove-counter :event :runner-turn-begins)]})

   "Political Operative"
   {:req (req (some #{:hq} (:successful-run runner-reg)))
    :abilities [{:effect
                 (effect
                   (continue-ability
                     ;; TODO: Convert this to a cost
                     {:prompt "Select a rezzed card with a trash cost"
                      :choices {:card #(and (:trash %)
                                            (rezzed? %)
                                            (can-pay? state side eid card nil [:credit (trash-cost state :runner %)]))}
                      :effect (effect
                                (continue-ability
                                  {:async true
                                   :msg (msg "trash " (card-str state target))
                                   :cost [:credit (trash-cost state :runner target) :trash]
                                   :effect (effect (trash eid target nil))}
                                  card targets))}
                     card nil))}]}

   "Power Tap"
   {:events [{:event :pre-init-trace
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits :runner 1))}]}

   "Professional Contacts"
   {:abilities [{:cost [:click 1]
                 :msg "gain 1 [Credits] and draw 1 card"
                 :async true
                 :effect (effect (gain-credits 1)
                                 (draw eid 1 nil))}]}

   "Psych Mike"
   {:events [{:event :successful-run-ends
              :req (req (first-event? state side :successful-run-ends #(= :rd (first (:server (first %))))))
              :msg (msg "gain " (total-cards-accessed target :deck) " [Credits]")
              :effect (effect (gain-credits :runner (total-cards-accessed target :deck)))}]}

   "Public Sympathy"
   {:in-play [:hand-size 2]}

   "Rachel Beckman"
   (trash-when-tagged-contructor "Rachel Beckman" {:in-play [:click-per-turn 1]})

   "Raymond Flint"
   {:events [{:event :corp-gain-bad-publicity
              :effect (req (wait-for
                             ;; manually trigger the pre-access event to alert Nerve Agent.
                             (trigger-event-sync state side :pre-access :hq)
                             (let [from-hq (access-count state side :hq-access)
                                   ;; see note in Gang Sign
                                   already-accessed (set (get-in @state [:corp :servers :hq :content]))
                                   ability (access-helper-hq state from-hq already-accessed)]
                               (resolve-ability state side ability card nil)))) }]
    :abilities [{:msg "expose 1 card"
                 :label "Expose 1 installed card"
                 :choices {:card installed?}
                 :async true
                 :cost [:trash]
                 :effect (effect (expose eid target))}]}

   "Reclaim"
   {:abilities
    [{:label "Install a program, piece of hardware, or virtual resource from your Heap"
      :cost [:click 1 :trash :trash-from-hand]
      :effect
      (effect
        (continue-ability
          {:prompt "Choose a card to install"
           :choices (req (conj (vec (sort-by
                                      :title
                                      (filter #(and (or (program? %)
                                                        (hardware? %)
                                                        (and (resource? %)
                                                             (has-subtype? % "Virtual")))
                                                    (can-pay? state :runner eid card nil (:cost %)))
                                              (:discard runner))))
                               "No install"))
           :msg (msg (if (= target "No install")
                       (str "search the heap, but does not find anything to install")
                       (str "install " (:title target) " from the heap")))
           :async true
           :effect (req (if (not= target "No install")
                          (runner-install state :runner (assoc eid :source card :source-type :runner-install) target nil)
                          (effect-completed state side eid)))}
          card nil))}]}

   "Rogue Trading"
   {:data {:counter {:credit 18}}
    :abilities [{:cost [:click 2]
                 :msg "gain 6 [Credits] and take 1 tag"
                 :effect (req (let [credits (min 6 (get-counters card :credit))]
                                (add-counter state side card :credit (- credits))
                                (gain-credits state :runner 6))
                              (when (not (pos? (get-counters (get-card state card) :credit)))
                                (trash state :runner card {:unpreventable true}))
                              (gain-tags state :runner eid 1))}]}

   "Rolodex"
   {:async true
    :msg "look at the top 5 cards of their Stack"
    :effect (req (show-wait-prompt state :corp "Runner to rearrange the top cards of their Stack")
                 (let [from (take 5 (:deck runner))]
                   (if (pos? (count from))
                     (continue-ability
                       state side
                       (reorder-choice :runner :corp from '() (count from) from)
                       card nil)
                     (do (clear-wait-prompt state :corp)
                         (effect-completed state side eid)))))
    :trash-effect {:effect (effect (system-msg :runner
                                               (str "trashes "
                                                    (join ", " (map :title (take 3 (:deck runner))))
                                                    " from their Stack due to Rolodex being trashed"))
                                   (mill :runner 3))}}

   "Rosetta 2.0"
   (let [find-rfg (fn [state card]
                    (last (filter #(= (:cid card) (get-in % [:persistent :from-cid]))
                                  (get-in @state [:runner :rfg]))))]
     {:abilities [{:req (req (not (install-locked? state side)))
                   :async true
                   :cost [:click 1 :rfg-program 1]
                   :effect
                   (effect
                     (continue-ability
                       {:async true
                        :prompt "Choose a non-virus program to install"
                        :choices (req (concat
                                        (->> (:deck runner)
                                             (filter
                                               #(and (program? %)
                                                     (not (has-subtype? % "Virus"))
                                                     (can-pay? state :runner eid card nil
                                                               [:credit (install-cost
                                                                          state side %
                                                                          {:cost-bonus (- (:cost (find-rfg state card)))})])))
                                             (sort-by :title)
                                             (into []))
                                        ["No install"]))
                        :msg (msg "search the stack"
                                  (if (= target "No install")
                                    ", but does not find a program to install"
                                    (str "and install " (:title target)
                                         ", lowering its cost by " (:cost (find-rfg state card)))))
                        :effect (req (trigger-event state side :searched-stack nil)
                                     (shuffle! state side :deck)
                                     (if (= target "No install")
                                       (effect-completed state side eid)
                                       (runner-install state side (assoc eid :source card :source-type :runner-install)
                                                       target {:cost-bonus (- (:cost (find-rfg state card)))})))}
                       card nil))}]})

   "Sacrificial Clone"
   {:interactions {:prevent [{:type #{:net :brain :meat}
                              :req (req true)}]}
    :abilities [{:cost [:trash]
                 :effect (req (doseq [c (concat (get-in runner [:rig :hardware])
                                                (filter #(not (has-subtype? % "Virtual"))
                                                        (get-in runner [:rig :resource]))
                                                (:hand runner))]
                                (trash state side c))
                              (lose-credits state side :all)
                              (lose-tags state side :all)
                              (lose state side :run-credit :all)
                              (damage-prevent state side :net Integer/MAX_VALUE)
                              (damage-prevent state side :meat Integer/MAX_VALUE)
                              (damage-prevent state side :brain Integer/MAX_VALUE))}]}

   "Sacrificial Construct"
   {:interactions {:prevent [{:type #{:trash-program :trash-hardware}
                              :req (req true)}]}
    :abilities [{:cost [:trash]
                 :effect (effect (trash-prevent :program 1)
                                 (trash-prevent :hardware 1))}]}

   "Safety First"
   {:in-play [:hand-size -2]
    :events [{:event :runner-turn-ends
              :async true
              :effect (req (if (< (count (:hand runner)) (hand-size state :runner))
                             (do (system-msg state :runner (str "uses " (:title card) " to draw a card"))
                                 (draw state :runner eid 1 nil))
                             (effect-completed state :runner eid)))}]}

   "Salsette Slums"
   {:interactions
    {:access-ability
     {:label "Remove card from game"
      :req (req (and (not (get-in @state [:per-turn (:cid card)]))
                     (:trash target)
                     (can-pay? state :runner {:source card :source-type :ability}
                               card (:title target) [:credit (trash-cost state side target)])))
      :once :per-turn
      :async true
      :trash? false
      :effect (req (let [trash-cost (trash-cost state side target)]
                     (wait-for (pay-sync state side (make-eid state eid) card [:credit trash-cost])
                               (move state :corp target :rfg)
                               (system-msg state side
                                           (str "pay " trash-cost
                                                " [Credits] and remove " (:title target)
                                                " from the game"))
                               (effect-completed state side eid))))}}}

   "Salvaged Vanadis Armory"
   {:events [{:event :damage
              :effect (req (show-wait-prompt state :corp "Runner to use Salvaged Vanadis Armory")
                           (resolve-ability
                             state :runner
                             {:optional
                              {:prompt "Use Salvaged Vanadis Armory?"
                               :yes-ability {:msg (msg "force the Corp to trash the top "
                                                       (get-turn-damage state :runner)
                                                       " cards of R&D and trash itself")
                                             :effect (effect (mill :corp (get-turn-damage state :runner))
                                                             (clear-wait-prompt :corp)
                                                             (trash card {:unpreventable true}))}
                               :no-ability {:effect (effect (clear-wait-prompt :corp))}}}
                             card nil))}]}

   "Same Old Thing"
   {:abilities [{:cost [:click 2 :trash]
                 :req (req (and (not (seq (get-in @state [:runner :locked :discard])))
                                (pos? (count (filter event? (:discard runner))))))
                 :prompt "Select an event to play"
                 :msg (msg "play " (:title target))
                 :show-discard true
                 :choices {:card #(and (event? %)
                                       (in-discard? %))}
                 :effect (effect (play-instant target))}]}

   "Scrubber"
   {:recurring 2
    :interactions {:pay-credits {:req (req (and (= :runner-trash-corp-cards (:source-type eid))
                                                (corp? target)))
                                 :type :recurring}}}

   "Security Testing"
   (let [ability {:prompt "Choose a server for Security Testing"
                  :choices (req (conj servers "No server"))
                  :msg (msg "target " target)
                  :req (req (and (:runner-phase-12 @state)
                                 (not (used-this-turn? (:cid card) state))))
                  :effect (req (when (not= target "No server")
                                 (update! state side (assoc card :server-target target))))}]
     {:events [(assoc ability :event :runner-turn-begins)
               {:event :successful-run
                :req (req (= (zone->name (get-in @state [:run :server]))
                             (:server-target (get-card state card))))
                :once :per-turn
                :silent (req true)
                :effect (effect (add-run-effect
                                  {:card card
                                   :replace-access
                                   {:mandatory true
                                    :msg "gain 2 [Credits] instead of accessing"
                                    :effect (effect (gain-credits 2)
                                                    (update! (dissoc (get-card state card) :server-target)))}}))}
               {:event :runner-turn-ends
                :effect (effect (update! (dissoc card :server-target)))}]
      :abilities [ability]})

   "Slipstream"
   {:implementation "Use Slipstream before hitting Continue to pass current ice"
    :abilities [{:req (req (:run @state))
                 :effect (req (let [ice-pos  (get-in @state [:run :position])]
                                (resolve-ability
                                  state side
                                  {:prompt (msg "Choose a piece of ICE protecting a central server at the same position as " (:title current-ice))
                                   :choices {:card #(and (is-central? (second (:zone %)))
                                                         (ice? %)
                                                         (= ice-pos (inc (ice-index state %))))}
                                   :msg (msg "approach " (card-str state target))
                                   :effect (req (let [dest (second (:zone target))]
                                                  (swap! state update-in [:run]
                                                         #(assoc % :position ice-pos :server [dest]))
                                                  (trash state side card)))}
                                  card nil)))}]}

   "Spoilers"
   {:events [{:event :agenda-scored
              :interactive (req true)
              :msg "trash the top card of R&D"
              :effect (effect (mill :corp))}]}

   "Starlight Crusade Funding"
   {:msg "ignore additional costs on Double events"
    :effect (req (swap! state assoc-in [:runner :register :double-ignore-additional] true))
    :events [{:event :runner-turn-begins
              :msg "lose [Click] and ignore additional costs on Double events"
              :effect (req (lose state :runner :click 1)
                           (swap! state assoc-in [:runner :register :double-ignore-additional] true))}]
    :leave-play (req (swap! state update-in [:runner :register] dissoc :double-ignore-additional))}

   "Stim Dealer"
   {:events [{:event :runner-turn-begins
              :effect (req (if (>= (get-counters card :power) 2)
                             (do (add-counter state side card :power (- (get-counters card :power)))
                                 (damage state side eid :brain 1 {:unpreventable true :card card})
                                 (system-msg state side "takes 1 brain damage from Stim Dealer"))
                             (do (add-counter state side card :power 1)
                                 (gain state side :click 1)
                                 (system-msg state side "uses Stim Dealer to gain [Click]"))))}]}

   "Street Magic"
   (letfn [(runner-break [unbroken-subs]
             {:prompt "Resolve a subroutine"
              :choices unbroken-subs
              :effect (req (let [sub (first (filter #(and (not (:broken %))
                                                          (= target (make-label (:sub-effect %))))
                                                    (:subroutines current-ice)))]
                             (wait-for (resolve-ability state side (make-eid state {:source-type :subroutine})
                                                        (:sub-effect sub) current-ice nil)
                                       (if (and (:run @state)
                                                (not (:ended (:run @state)))
                                                (rest unbroken-subs))
                                         (continue-ability
                                           state side
                                           (runner-break (remove-once #(= target %) unbroken-subs))
                                           card nil)
                                         (effect-completed state side eid)))))})]
     {:abilities [{:implementation "Effect is manually triggered"
                   :req (req (pos? (count (remove :broken (:subroutines current-ice)))))
                   :async true
                   :msg (msg "select the order the unbroken subroutines on "
                          (:title current-ice) " resolve")
                   :effect (effect
                             (continue-ability
                               (let [unbroken-subs (unbroken-subroutines-choice current-ice)]
                                 (runner-break unbroken-subs))
                               card nil))}]})

   "Street Peddler"
   {:interactive (req (some #(card-flag? % :runner-install-draw true) (all-active state :runner)))
    :effect (req (doseq [c (take 3 (:deck runner))]
                   (host state side (get-card state card) c {:facedown true})))
    :abilities [{:async true
                 :req (req (not (install-locked? state side)))
                 :prompt "Choose a card on Street Peddler to install"
                 :choices (req (cancellable
                                 (filter #(and (not (event? %))
                                               (runner-can-install? state side % nil)
                                               (can-pay? state side eid card nil
                                                         [:credit (install-cost state side % {:cost-bonus -1})]))
                                         (:hosted card))))
                 :msg (msg "install " (:title target) " lowering its install cost by 1 [Credits]")
                 :effect (req (trash state side (update-in card [:hosted]
                                                           (fn [coll]
                                                             (remove-once #(same-card? % target) coll)))
                                     {:cause :ability-cost})
                              (runner-install state side (assoc eid :source card :source-type :runner-install)
                                              (dissoc target :facedown) {:cost-bonus -1}))}]}

   "Symmetrical Visage"
   {:events [{:event :runner-click-draw
              :req (req (genetics-trigger? state side :runner-click-draw))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]}

   "Synthetic Blood"
   {:events [{:event :damage
              :req (req (genetics-trigger? state side :damage))
              :msg "draw 1 card"
              :async true
              :effect (effect (draw :runner eid 1 nil))}]}

   "Tallie Perrault"
   {:abilities [{:label "Draw 1 card for each Corp bad publicity"
                 :async true
                 :cost [:trash]
                 :effect (effect (draw eid (count-bad-pub state) nil))
                 :msg (msg "draw " (count-bad-pub state) " cards")}]
    :events [{:event :play-operation
              :req (req (or (has-subtype? target "Black Ops")
                            (has-subtype? target "Gray Ops")))
              :effect (req (show-wait-prompt state :corp "Runner to use Tallie Perrault")
                           (resolve-ability
                             state :runner
                             {:optional
                              {:prompt "Use Tallie Perrault to give the Corp 1 bad publicity and take 1 tag?"
                               :player :runner
                               :yes-ability {:msg "give the Corp 1 bad publicity and take 1 tag"
                                             :async true
                                             :effect (effect (gain-bad-publicity :corp 1)
                                                             (gain-tags :runner eid 1)
                                                             (clear-wait-prompt :corp))}
                               :no-ability {:effect (effect (clear-wait-prompt :corp))}}}
                             card nil))}]}

   "Tech Trader"
   {:events [{:event :runner-trash
              :req (req (and (= side :runner)
                             (= (second targets) :ability-cost)))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]}

   "Technical Writer"
   {:events [{:event :runner-install
              :silent (req true)
              :req (req (and (or (hardware? target)
                                 (program? target))
                             (not (facedown? target))))
              :effect (effect (add-counter :runner card :credit 1)
                              (system-msg (str "places 1 [Credits] on Technical Writer")))}]
    :abilities [{:cost [:click 1 :trash]
                 :msg (msg "gain " (get-counters card :credit) " [Credits]")
                 :effect (effect (gain-credits (get-counters card :credit)))}]}

   "Temple of the Liberated Mind"
   {:abilities [{:cost [:click 1]
                 :label "Place 1 power counter"
                 :msg "place 1 power counter on it"
                 :effect (effect (add-counter card :power 1))}
                {:label "Gain [Click]"
                 :cost [:power 1]
                 :req (req (= (:active-player @state) :runner))
                 :msg "gain [Click]" :once :per-turn
                 :effect (effect (gain :click 1))}]}

   "Temüjin Contract"
   {:data {:counter {:credit 20}}
    :prompt "Choose a server for Temüjin Contract"
    :choices (req servers)
    :msg (msg "target " target)
    :req (req (not (:server-target card)))
    :effect (effect (update! (assoc card :server-target target)))
    :events [{:event :successful-run
              :req (req (= (zone->name (get-in @state [:run :server])) (:server-target (get-card state card))))
              :msg (msg "gain " (min 4 (get-counters card :credit)) " [Credits]")
              :effect (req (let [credits (min 4 (get-counters card :credit))]
                             (add-counter state side card :credit (- credits))
                             (gain-credits state side credits))
                           (when (not (pos? (get-counters (get-card state card) :credit)))
                             (trash state side card {:unpreventable true})))}]}

   "The Archivist"
   {:in-play [:link 1]
    :events [{:event :agenda-scored
              :req (req (or (has-subtype? target "Initiative")
                            (has-subtype? target "Security")))
              :interactive (req true)
              :async true
              :msg "force the Corp to initiate a trace"
              :label "Trace 1 - If unsuccessful, take 1 bad publicity"
              :trace {:base 1
                      :unsuccessful
                      {:effect (effect (gain-bad-publicity :corp 1)
                                       (system-msg :corp (str "takes 1 bad publicity")))}}}]}

   "The Artist"
   {:abilities [{:cost [:click 1]
                 :label "Gain 2 [Credits]"
                 :msg "gain 2 [Credits]"
                 :once :per-turn
                 :once-key :artist-credits
                 :effect (effect (gain-credits 2))}
                {:cost [:click 1]
                 :label "Install a program of piece of hardware"
                 :req (req (some #(and (or (hardware? %)
                                           (program? %))
                                       (can-pay? state side eid card nil
                                                 [:credit (install-cost state side % {:cost-bonus -1})]))
                                 (:hand runner)))
                 :prompt "Select a program or piece of hardware to install from your Grip"
                 :choices
                 {:req (req (and (or (hardware? target)
                                     (program? target))
                                 (in-hand? target)
                                 (can-pay? state side eid card nil
                                           [:credit (install-cost state side target {:cost-bonus -1})])))}
                 :once :per-turn
                 :once-key :artist-install
                 :async true
                 :effect (effect (runner-install (assoc eid :source card :source-type :runner-install)
                                                 target {:no-msg true
                                                         :cost-bonus -1}))
                 :msg (msg "install " (:title target) ", lowering its cost by 1 [Credits]")}]}

   "The Black File"
   {:msg "prevent the Corp from winning the game unless they are flatlined"
    :effect (req (swap! state assoc-in [:corp :cannot-win-on-points] true))
    :events [{:event :runner-turn-begins
              :effect (req (if (>= (get-counters card :power) 2)
                             (do (move state side card :rfg)
                                 (swap! state update :corp dissoc :cannot-win-on-points)
                                 (system-msg state side "removes The Black File from the game")
                                 (gain-agenda-point state :corp 0))
                             (add-counter state side card :power 1)))}]
    :trash-effect {:effect (req (swap! state update :corp dissoc :cannot-win-on-points)
                                (gain-agenda-point state :corp 0))}
    :leave-play (req (swap! state update :corp dissoc :cannot-win-on-points)
                     (gain-agenda-point state :corp 0))}

   "The Class Act"
   (let [draw-ability {:req (req (= :this-turn (installed? card)))
                       :async true
                       :msg "draw 4 cards"
                       :effect (effect (draw :runner eid 4 nil))}]
     {:events [(assoc draw-ability :event :corp-turn-ends)
               (assoc draw-ability :event :runner-turn-ends)
               {:event :pre-runner-draw
                :msg "draw 1 additional card"
                ;; The req catches draw events that happened before The Class Act was installed
                :req (req (first-event? state :runner :pre-runner-draw))
                :async true
                :interactive (req true)
                :once :per-turn
                :effect (req (if (zero? (count (get-in @state [:runner :deck])))
                               (effect-completed state side eid)
                               (let [n (+ target (get-in @state [:bonus :draw] 0))
                                     to-draw (take (inc n) (:deck (:runner @state)))]
                                 (show-wait-prompt state :corp "Runner to use The Class Act")
                                 (continue-ability
                                   state :runner
                                   {:prompt "Select 1 card to add to the bottom of the stack"
                                    :msg "add 1 card to the bottom of the Stack"
                                    :choices to-draw
                                    :effect (effect (move target :deck)
                                                    (clear-wait-prompt :corp)
                                                    (effect-completed eid))}
                                   card nil))))}]})

   "The Helpful AI"
   {:in-play [:link 1]
    :abilities [{:msg (msg "give +2 strength to " (:title target))
                 :choices {:card #(and (has-subtype? % "Icebreaker")
                                       (installed? %))}
                 :cost [:trash]
                 :effect (effect (pump target 2 :end-of-turn))}]}

   "The Nihilist"
   (let [has-2-virus-tokens? (req (<= 2 (number-of-virus-counters state)))
         corp-choice {:optional {:player :corp
                                 :prompt "Trash the top card of R&D to prevent the Runner drawing 2 cards?"
                                 :async true
                                 :yes-ability {:effect (effect (clear-wait-prompt :runner)
                                                               (system-msg :corp "trashes the top card of R&D to prevent the Runner drawing 2 cards")
                                                               (mill :corp)
                                                               (effect-completed eid))}
                                 :no-ability {:async true
                                              :effect (effect (clear-wait-prompt :runner)
                                                              (system-msg :runner "draw 2 cards")
                                                              (draw :runner eid 2 nil))}}}
         maybe-spend-2 {:prompt "Spend 2 virus counters on The Nihilist?"
                        :async true
                        :yes-ability
                        {:effect (req (wait-for (resolve-ability state side (pick-virus-counters-to-spend 2) card nil)
                                                (if (:number async-result)
                                                  (do (system-msg state side (str "spends " (:msg async-result) " on The Nihilist"))
                                                      (show-wait-prompt state :runner "Corp to decide")
                                                      (continue-ability state side corp-choice card nil))
                                                  (effect-completed state side eid))))}}]
     {:events [{:event :runner-turn-begins
                :interactive (req true)
                :req has-2-virus-tokens?
                :optional maybe-spend-2}
               {:event :runner-install
                :msg "add 2 virus tokens to The Nihilist"
                :effect (effect (add-counter card :virus 2))
                :req (req (has-subtype? target "Virus"))
                :once :per-turn}]})

   "The Shadow Net"
   (letfn [(events [runner] (filter #(and (event? %) (not (has-subtype? % "Priority"))) (:discard runner)))]
     {:abilities [{:cost [:click 1 :forfeit]
                   :req (req (pos? (count (events runner))))
                   :label "Play an event from your Heap, ignoring all costs"
                   :prompt "Choose an event to play"
                   :msg (msg "play " (:title target) " from the Heap, ignoring all costs")
                   :choices (req (cancellable (events runner) :sorted))
                   :effect (effect (play-instant nil target {:ignore-cost true}))}]})

   "The Source"
   {:effect (effect (update-all-advancement-costs))
    :leave-play (effect (update-all-advancement-costs))
    :events [{:event :agenda-scored
              :effect (effect (trash card))}
             {:event :agenda-stolen
              :effect (effect (trash card))}
             {:event :pre-advancement-cost
              :effect (effect (advancement-cost-bonus 1))}
             {:event :pre-steal-cost
              :effect (effect (steal-cost-bonus [:credit 3]))}]}

   "The Supplier"
   (let [ability {:label "Install a hosted card (start of turn)"
                  :prompt "Choose a card hosted on The Supplier to install"
                  :req (req (some #(can-pay? state side eid card nil [:credit (install-cost state side % {:cost-bonus -2})])
                                  (:hosted card)))
                  :choices
                  {:req (req (and (= "The Supplier" (:title (:host target)))
                                  (runner? target)
                                  (can-pay? state side eid card nil
                                            [:credit (install-cost state side target {:cost-bonus -2})])))}
                  :once :per-turn
                  :msg (msg "install " (:title target)
                            ", lowering its install cost by 2 [Credits]")
                  :async true
                  :effect
                  (req (if-not (runner-can-install? state side target nil)
                         (effect-completed state side eid)
                         (do (update! state side
                                      (-> card
                                          (assoc :supplier-installed (:cid target))
                                          (update-in [:hosted]
                                                     (fn [coll]
                                                       (remove-once #(same-card? % target) coll)))))
                             (runner-install state side (assoc eid :source card :source-type :runner-install)
                                             target {:cost-bonus -2}))))}]
     {:flags {:drip-economy true}  ; not technically drip economy, but has an interaction with Drug Dealer
      :abilities [{:label "Host a resource or piece of hardware"
                   :cost [:click 1]
                   :prompt "Select a card to host on The Supplier"
                   :choices {:card #(and (or (hardware? %)
                                             (resource? %))
                                         (in-hand? %))}
                   :effect (effect (host card target))
                   :msg (msg "host " (:title target))}
                  ability]
      ; A card installed by The Supplier is ineligible to receive the turn-begins event for this turn.
      :suppress [{:event :runner-turn-begins
                  :req (req (= (:cid target) (:supplier-installed (get-card state card))))}]
      :events [(assoc ability :event :runner-turn-begins)
               {:event :runner-turn-ends
                :req (req (:supplier-installed card))
                :effect (effect (update! (dissoc card :supplier-installed)))}]})

   "The Turning Wheel"
   (let [ttw-ab (fn [m s]
                  {:label (str "Access an additional card in " m)
                   :cost [:power 2]
                   :req (req run)
                   :msg (msg "access 1 additional card from " m " for the remainder of the run")
                   :effect (req (access-bonus state side s 1))})]
     {:events [{:event :agenda-stolen
                :effect (effect (update! (assoc card :agenda-stolen true)))
                :silent (req true)}
               {:event :run-ends
                :effect (req (when (and (not (:agenda-stolen card))
                                        (#{:hq :rd} target))
                               (add-counter state side card :power 1)
                               (system-msg state :runner (str "places a power counter on " (:title card))))
                             (update! state side (dissoc (get-card state card) :agenda-stolen)))
                :silent (req true)}]
      :abilities [(ttw-ab "R&D" :rd)
                  (ttw-ab "HQ" :hq)]})

   "Theophilius Bagbiter"
   {:effect (req (lose-credits state :runner :all)
                 (lose state :runner :run-credit :all)
                 (swap! state assoc-in [:runner :hand-size :base] 0)
                 (add-watch state :theophilius-bagbiter
                            (fn [k ref old new]
                              (let [credit (get-in new [:runner :credit])]
                                (when (not= (get-in old [:runner :credit]) credit)
                                  (swap! ref assoc-in [:runner :hand-size :base] credit))))))
    :leave-play (req (remove-watch state :theophilius-bagbiter)
                     (swap! state assoc-in [:runner :hand-size :base] 5))}

   "Thunder Art Gallery"
   (let [first-event-check (fn [state fn1 fn2] (and (fn1 state :runner :runner-lose-tag #(= :runner (second %)))
                                                    (fn2 state :runner :runner-prevent (fn [t] (seq (filter #(some #{:tag} %) t))))))
         ability {:choices
                  {:req (req (and (runner? target)
                                  (in-hand? target)
                                  (not (event? target))
                                  (can-pay? state side eid card nil
                                            [:credit (install-cost state side target {:cost-bonus -1})])))}
                  :async true
                  :prompt (msg "Select a card to install with Thunder Art Gallery")
                  :msg (msg "install " (:title target))
                  :effect (effect (runner-install (assoc eid
                                                         :source card
                                                         :source-type :runner-install)
                                                  target {:cost-bonus -1}))
                  :cancel-effect (effect (effect-completed eid))}]
     {:events [(assoc ability
                      :event :runner-lose-tag
                      :req (req (and (first-event-check state first-event? no-event?) (= side :runner))))
               (assoc ability
                      :event :runner-prevent
                      :req (req (and (first-event-check state no-event? first-event?) (seq (filter #(some #{:tag} %) targets)))))]})

   "Trickster Taka"
   (assoc
     (companion-builder
       (req (and (pos? (get-counters (get-card state card) :credit))
                 run
                 (not (:successful run))
                 (not (:unsuccessful run))))
       (effect (show-wait-prompt :corp "Runner to take decision on Trickster Taka")
               (continue-ability
                 {:prompt "Take 1 tag or trash Trickster Taka?"
                  :choices ["Take 1 tag" "Trash"]
                  :player :runner
                  :async true
                  :effect (req (clear-wait-prompt state :corp)
                               (if (= target "Trash")
                                 (do
                                   (trash state :runner card)
                                   (system-msg state :runner "trashes Trickster Taka")
                                   (effect-completed state side eid))
                                 (do
                                   (system-msg state :runner "takes 1 tag to avoid trashing Trickster Taka")
                                   (gain-tags state :runner eid 1))))}
                 card nil))
       {:msg "take 1 [Credits]"
        :async true
        :effect (effect (add-counter card :credit -1)
                        (gain-credits 1)
                        (trigger-event-sync eid :spent-stealth-credit card))})
     :interactions {:pay-credits {:req (req (and (= :ability (:source-type eid))
                                                 (program? target)
                                                 run))
                                  :type :credit}})

   "Tri-maf Contact"
   {:abilities [{:cost [:click 1]
                 :msg "gain 2 [Credits]"
                 :once :per-turn
                 :effect (effect (gain-credits 2))}]
    :trash-effect {:async true
                   :effect (effect (damage eid :meat 3 {:unboostable true :card card}))}}

   "Tyson Observatory"
   {:abilities [{:prompt "Choose a piece of Hardware" :msg (msg "add " (:title target) " to their Grip")
                 :choices (req (cancellable (filter hardware? (:deck runner)) :sorted))
                 :cost [:click 2]
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (move target :hand))}]}

   "Underworld Contact"
   (let [ability {:label "Gain 1 [Credits] (start of turn)"
                  :once :per-turn
                  :effect (req (when (and (>= (:link runner) 2)
                                          (:runner-phase-12 @state))
                                 (system-msg state :runner (str "uses " (:title card) " to gain 1 [Credits]"))
                                 (gain-credits state :runner 1)))}]
     {:flags {:drip-economy true}
      :abilities [ability]
      :events [(assoc ability :event :runner-turn-begins)]})

   "Utopia Shard"
   (shard-constructor "Utopia Shard" :hq "force the Corp to discard 2 cards from HQ at random"
                      (effect (trash-cards :corp eid (take 2 (shuffle (:hand corp))) nil)))

   "Virus Breeding Ground"
   {:events [{:event :runner-turn-begins
              :effect (effect (add-counter card :virus 1))}]
    :abilities [{:cost [:click 1]
                 :req (req (pos? (get-counters card :virus)))
                 :effect (req (resolve-ability
                                state side
                                {:msg (msg "move 1 virus counter to " (:title target))
                                 :choices {:card #(pos? (get-virus-counters state %))}
                                 :effect (req (add-counter state side card :virus -1)
                                              (add-counter state side target :virus 1))}
                                card nil))}]}

   "Wasteland"
   {:events [{:event :runner-trash
              :req (req (and (first-installed-trash-own? state :runner)
                             (installed? target)
                             (runner? target)))
              :msg "gain 1 [Credits]"
              :effect (effect (gain-credits 1))}]}

   "Whistleblower"
   (letfn [(steal-events [named-agenda]
             [{:event :run-ends
               :effect (effect (unregister-events card {:events [{:event :access}
                                                                 {:event :run-ends}]}))}
              {:event :access
               :req (req (= (:title target) named-agenda))
               :once :per-run
               :async true
               :effect (effect (steal eid target))}])]
     {:events [{:event :successful-run
                :optional {:autoresolve (get-autoresolve :auto-name-agenda)
                           :prompt "Trash Whistleblower to name an agenda?"
                           :yes-ability {:prompt "Name an agenda"
                                         :choices {:card-title (req (and (corp? target)
                                                                         (agenda? target)))}
                                         :effect (effect (system-msg (str "trashes " (:title card)
                                                                          " to name " (:title target)))
                                                   (register-events (dissoc card :zone)
                                                                    (steal-events target))
                                                   (trash eid card {:unpreventable true
                                                                    :cause :ability-cost}))}}}]
      :abilities [(set-autoresolve :auto-name-agenda "Whistleblower's ability")]})

   "Wireless Net Pavilion"
   {:effect (effect (trash-resource-bonus -2))
    :leave-play (effect (trash-resource-bonus 2))
    :implementation "Errata from FAQ 3.0.1: should be unique"}

   "Woman in the Red Dress"
   (let [ability {:msg (msg "reveal " (:title (first (:deck corp))) " on the top of R&D")
                  :label "Reveal the top card of R&D (start of turn)"
                  :once :per-turn
                  :req (req (:runner-phase-12 @state))
                  :effect (effect (reveal (:title (first (:deck corp))))
                                  (show-wait-prompt :runner "Corp to decide whether or not to draw with Woman in the Red Dress")
                                  (resolve-ability
                                    {:optional
                                     {:player :corp
                                      :prompt (msg "Draw " (:title (first (:deck corp))) "?")
                                      :yes-ability {:effect (effect (clear-wait-prompt :runner)
                                                                    (system-msg (str "draws " (:title (first (:deck corp)))))
                                                                    (draw))}
                                      :no-ability {:effect (effect (clear-wait-prompt :runner)
                                                                   (system-msg "doesn't draw with Woman in the Red Dress"))}}}
                                    card nil))}]
     {:events [(assoc ability :event :runner-turn-begins)]
      :abilities [ability]})

   "Wyldside"
   {:flags {:runner-turn-draw true
            :runner-phase-12 (req (< 1 (count (filter #(card-flag? % :runner-turn-draw true)
                                                      (cons (get-in @state [:runner :identity])
                                                            (all-active-installed state :runner))))))}
    :events [{:event :runner-turn-begins
              :async true
              :effect (req (lose state side :click 1)
                           (if (get-in @state [:per-turn (:cid card)])
                             (effect-completed state side eid)
                             (do (system-msg state side "uses Wyldside to draw 2 cards and lose [Click]")
                                 (draw state side eid 2 nil))))}]
    :abilities [{:msg "draw 2 cards and lose [Click]"
                 :once :per-turn
                 :async true
                 :effect (effect (draw eid 2 nil))}]}

   "Xanadu"
   {:constant-effects [{:type :rez-cost
                        :req (req (ice? target))
                        :value 1}]}

   "Zona Sul Shipping"
   (trash-when-tagged-contructor
     "Zona Sul Shipping"
     {:events [{:event :runner-turn-begins
                :effect (effect (add-counter card :credit 1))}]
      :abilities [{:cost [:click 1]
                   :msg (msg "gain " (get-counters card :credit) " [Credits]")
                   :label "Take all credits"
                   :effect (effect (gain-credits (get-counters card :credit))
                                   (add-counter card :credit
                                                (- (get-counters card :credit))))}]})})
